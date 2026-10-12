import type { SQLiteBindValue, SQLiteRunResult } from 'expo-sqlite';
import { createTelemetryEvent } from '../telemetry/createTelemetryEvent';
import {
  initializeTelemetryDatabase,
  type TelemetryDatabase,
  type TelemetryTransaction,
} from './database';
import {
  getSyncSummary,
  listPendingTelemetryEvents,
  listTelemetryEvents,
  saveTelemetryEvent,
  updateTelemetrySyncResults,
} from './telemetryStore';

let mockUuidCounter = 0;
jest.mock('expo-crypto', () => ({
  randomUUID: jest.fn(() => `11111111-1111-4111-8111-${String(++mockUuidCounter).padStart(12, '0')}`),
}));

class PersistentSqliteDouble implements TelemetryDatabase {
  readonly rows: Map<string, Record<string, unknown>>;
  schema: string[] = [];

  constructor(rows: Map<string, Record<string, unknown>> = new Map()) {
    this.rows = rows;
  }

  async execAsync(source: string): Promise<void> {
    this.schema.push(source);
  }

  async runAsync(
    sql: string,
    ...params: SQLiteBindValue[]
  ): Promise<SQLiteRunResult> {
    if (sql.trimStart().startsWith('UPDATE')) {
      const [status, errorCode, eventId] = params;
      if (typeof eventId !== 'string') throw new Error('Missing event ID');
      const row = this.rows.get(eventId);
      if (row && row.sync_status === 'pending') {
        row.sync_status = status;
        row.attempts = Number(row.attempts) + 1;
        row.last_error = errorCode;
      }
      return { changes: row ? 1 : 0, lastInsertRowId: 0 };
    }
    const [eventId, vehicleId, latitude, longitude, timestamp, speed, source, createdAt] = params;
    if (typeof eventId !== 'string' || typeof vehicleId !== 'string'
      || typeof latitude !== 'number' || typeof longitude !== 'number'
      || typeof timestamp !== 'string' || (speed !== null && typeof speed !== 'number')
      || typeof source !== 'string' || typeof createdAt !== 'string') {
      throw new Error(`Unexpected SQLite bindings: ${JSON.stringify(params)}`);
    }
    if (this.rows.has(eventId)) throw new Error('UNIQUE constraint failed: telemetry_events.event_id');
    this.rows.set(eventId, {
      event_id: eventId,
      vehicle_id: vehicleId,
      latitude,
      longitude,
      timestamp,
      speed,
      source,
      sync_status: 'pending',
      attempts: 0,
      created_at: createdAt,
      last_error: null,
    });
    return { changes: 1, lastInsertRowId: 1 };
  }

  async getAllAsync<T>(sql: string, ..._params: SQLiteBindValue[]): Promise<T[]> {
    const rows = [...this.rows.values()]
      .filter((row) => !sql.includes("WHERE sync_status = 'pending'") || row.sync_status === 'pending')
      .sort((left, right) => String(left.created_at).localeCompare(String(right.created_at)));
    return rows as T[];
  }

  async getFirstAsync<T>(_sql: string, ..._params: SQLiteBindValue[]): Promise<T | null> {
    let pending = 0;
    let synced = 0;
    for (const row of this.rows.values()) {
      if (row.sync_status === 'pending') pending += 1;
      if (row.sync_status === 'synced') synced += 1;
    }
    return { pending, synced } as T;
  }

  async withExclusiveTransactionAsync(task: (transaction: TelemetryTransaction) => Promise<void>) {
    const snapshot = new Map([...this.rows].map(([id, row]) => [id, { ...row }]));
    try {
      await task({ runAsync: (sql, ...params) => this.runAsync(sql, ...params) });
    } catch (error) {
      this.rows.clear();
      for (const [id, row] of snapshot) this.rows.set(id, row);
      throw error;
    }
  }
}

describe('local telemetry storage', () => {
  let rows: Map<string, Record<string, unknown>>;
  let database: TelemetryDatabase;
  let sqliteDouble: PersistentSqliteDouble;

  beforeEach(async () => {
    rows = new Map();
    sqliteDouble = new PersistentSqliteDouble(rows);
    database = sqliteDouble;
    await initializeTelemetryDatabase(database);
  });

  it('initializes the schema and starts with an empty summary', async () => {
    expect(sqliteDouble.schema[0]).toContain('CREATE TABLE IF NOT EXISTS telemetry_events');
    expect(sqliteDouble.schema[0]).toContain('event_id TEXT PRIMARY KEY');
    expect(await getSyncSummary(database)).toEqual({ pending: 0, synced: 0 });
  });

  it('saves and queries events as pending with the original payload', async () => {
    const event = createTelemetryEvent(
      { vehicleId: 'TRUCK-1', latitude: 37.7, longitude: -122.4, speed: 45 },
      'gps',
    );
    await saveTelemetryEvent(database, event);

    const [record] = await listPendingTelemetryEvents(database);
    expect(record.event).toEqual(event);
    expect(record.attempts).toBe(0);
    expect(record.lastError).toBeNull();
    expect(await getSyncSummary(database)).toEqual({ pending: 1, synced: 0 });
  });

  it('persists and reloads the captured speed without changing its value', async () => {
    const event = createTelemetryEvent(
      { vehicleId: 'TRUCK-1', latitude: 37.7, longitude: -122.4, speed: 36 },
      'gps',
    );
    await saveTelemetryEvent(database, event);

    expect((await listPendingTelemetryEvents(database))[0].event.speed).toBe(36);
    expect((await listTelemetryEvents(database))[0].event).toEqual(event);
  });

  it('rejects duplicate event IDs without replacing the original event', async () => {
    const event = createTelemetryEvent(
      { vehicleId: 'TRUCK-1', latitude: 10, longitude: 20 },
      'gps',
    );
    await saveTelemetryEvent(database, event);
    const conflictingEvent = Object.freeze({ ...event, vehicleId: 'TRUCK-2' });

    await expect(saveTelemetryEvent(database, conflictingEvent)).rejects.toThrow('UNIQUE constraint');
    expect((await listPendingTelemetryEvents(database))[0].event.vehicleId).toBe('TRUCK-1');
  });

  it('retains saved events after storage access is reinitialized', async () => {
    const event = createTelemetryEvent(
      { vehicleId: 'TRUCK-1', latitude: 10, longitude: 20 },
      'manual_simulated',
    );
    await saveTelemetryEvent(database, event);

    const reopenedDatabase = new PersistentSqliteDouble(rows);
    await initializeTelemetryDatabase(reopenedDatabase);

    expect((await listPendingTelemetryEvents(reopenedDatabase))[0].event).toEqual(event);
  });

  it('updates confirmed events transactionally while leaving unconfirmed events pending', async () => {
    const published = createTelemetryEvent({ vehicleId: 'TRUCK-1', latitude: 10, longitude: 20 }, 'gps');
    const unconfirmed = createTelemetryEvent({ vehicleId: 'TRUCK-2', latitude: 11, longitude: 21 }, 'gps');
    await saveTelemetryEvent(database, published);
    await saveTelemetryEvent(database, unconfirmed);

    await updateTelemetrySyncResults(database, [
      { eventId: published.eventId, status: 'synced', errorCode: null },
      { eventId: unconfirmed.eventId, status: 'pending', errorCode: 'PUBLICATION_UNCONFIRMED' },
    ]);

    expect(await getSyncSummary(database)).toEqual({ pending: 1, synced: 1 });
    expect(await listTelemetryEvents(database)).toHaveLength(2);
    expect((await listPendingTelemetryEvents(database))[0]).toMatchObject({
      event: unconfirmed,
      attempts: 1,
      lastError: 'PUBLICATION_UNCONFIRMED',
    });
  });
});
