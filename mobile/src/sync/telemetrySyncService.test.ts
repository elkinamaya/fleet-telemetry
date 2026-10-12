import type { SQLiteBindValue, SQLiteRunResult } from 'expo-sqlite';
import type { TelemetryDatabase, TelemetryTransaction } from '../storage/database';
import type { CapturedTelemetryEvent, SyncStatus } from '../telemetry/types';
import { createTelemetryEvent } from '../telemetry/createTelemetryEvent';
import { TelemetryBatchClientError } from './telemetryBatchClient';
import { createTelemetrySyncService } from './telemetrySyncService';

let mockUuidCounter = 0;
jest.mock('expo-crypto', () => ({
  randomUUID: jest.fn(() => `11111111-1111-4111-8111-${String(++mockUuidCounter).padStart(12, '0')}`),
}));

type Row = {
  event_id: string;
  vehicle_id: string;
  latitude: number;
  longitude: number;
  timestamp: string;
  speed: number | null;
  source: 'gps' | 'manual_simulated';
  sync_status: SyncStatus;
  attempts: number;
  created_at: string;
  last_error: string | null;
};

class SyncDatabaseDouble implements TelemetryDatabase {
  readonly rows = new Map<string, Row>();
  failUpdateOnCall?: number;
  private transactionUpdateCount = 0;

  async execAsync(): Promise<void> {}

  async runAsync(sql: string, ...params: SQLiteBindValue[]): Promise<SQLiteRunResult> {
    return this.update(sql, params);
  }

  async getAllAsync<T>(): Promise<T[]> {
    return [...this.rows.values()].filter((row) => row.sync_status === 'pending') as T[];
  }

  async getFirstAsync<T>(): Promise<T | null> {
    return null;
  }

  async withExclusiveTransactionAsync(task: (transaction: TelemetryTransaction) => Promise<void>) {
    const snapshot = new Map([...this.rows].map(([id, row]) => [id, { ...row }]));
    this.transactionUpdateCount = 0;
    try {
      await task({ runAsync: (sql, ...params) => this.update(sql, params) });
    } catch (error) {
      this.rows.clear();
      for (const [id, row] of snapshot) this.rows.set(id, row);
      throw error;
    }
  }

  add(event: CapturedTelemetryEvent, createdAt: string): void {
    this.rows.set(event.eventId, {
      event_id: event.eventId,
      vehicle_id: event.vehicleId,
      latitude: event.latitude,
      longitude: event.longitude,
      timestamp: event.timestamp,
      speed: event.speed,
      source: event.source,
      sync_status: 'pending',
      attempts: 0,
      created_at: createdAt,
      last_error: null,
    });
  }

  private async update(_sql: string, params: SQLiteBindValue[]): Promise<SQLiteRunResult> {
    const [status, errorCode, eventId] = params;
    this.transactionUpdateCount += 1;
    if (this.transactionUpdateCount === this.failUpdateOnCall) {
      throw new Error('simulated local storage failure');
    }
    if (typeof eventId !== 'string') throw new Error(`missing event ID: ${JSON.stringify(params)}`);
    const row = this.rows.get(eventId);
    if (row?.sync_status === 'pending') {
      row.sync_status = status as SyncStatus;
      row.attempts += 1;
      row.last_error = typeof errorCode === 'string' ? errorCode : null;
    }
    return { changes: row ? 1 : 0, lastInsertRowId: 0 };
  }
}

function createEvent(index: number) {
  return createTelemetryEvent({
    vehicleId: `TRUCK-${index}`,
    latitude: 10 + (index % 70),
    longitude: 20 + (index % 160),
    speed: 45,
  }, index % 2 === 0 ? 'gps' : 'manual_simulated');
}

function publishedResponse(events: readonly CapturedTelemetryEvent[]) {
  return {
    status: 202 as const,
    body: { results: events.map(({ eventId }) => ({ eventId, status: 'PUBLISHED' })) },
  };
}

describe('manual telemetry synchronization', () => {
  it('confirms all events from a valid 202 response by ID', async () => {
    const database = new SyncDatabaseDouble();
    const events = [createEvent(0), createEvent(1)];
    events.forEach((event, index) => database.add(event, `2026-10-12T14:0${index}:00Z`));
    const postBatch = jest.fn(async (sent: readonly CapturedTelemetryEvent[]) => publishedResponse(sent));

    const result = await createTelemetrySyncService({ postBatch })(database);

    expect(result).toMatchObject({ status: 'completed', total: 2, confirmed: 2, pending: 0 });
    expect(database.rows.get(events[0].eventId)).toMatchObject({ sync_status: 'synced', attempts: 1 });
  });

  it('sends and preserves each pending event original speed during synchronization', async () => {
    const database = new SyncDatabaseDouble();
    const captured = createTelemetryEvent({
      vehicleId: 'TRUCK-SPEED',
      latitude: 40,
      longitude: -70,
      speed: 36,
    }, 'gps');
    database.add(captured, '2026-10-12T14:00:00Z');
    const postBatch = jest.fn(async (sent: readonly CapturedTelemetryEvent[]) => publishedResponse(sent));

    await createTelemetrySyncService({ postBatch })(database);

    expect(postBatch).toHaveBeenCalledWith([captured]);
    expect(database.rows.get(captured.eventId)).toMatchObject({
      speed: 36,
      sync_status: 'synced',
    });
  });

  it('confirms only PUBLISHED IDs from a 207 response', async () => {
    const database = new SyncDatabaseDouble();
    const events = [createEvent(0), createEvent(1)];
    events.forEach((event, index) => database.add(event, `2026-10-12T14:0${index}:00Z`));
    const postBatch = jest.fn(async (sent: readonly CapturedTelemetryEvent[]) => ({
      status: 207 as const,
      body: {
        results: [
          { eventId: sent[1].eventId, status: 'PUBLICATION_UNCONFIRMED', errorCode: 'TELEMETRY_PUBLICATION_UNAVAILABLE' },
          { eventId: sent[0].eventId, status: 'PUBLISHED' },
        ],
      },
    }));

    const result = await createTelemetrySyncService({ postBatch })(database);

    expect(result).toMatchObject({ status: 'partial', confirmed: 1, pending: 1 });
    expect(database.rows.get(events[0].eventId)?.sync_status).toBe('synced');
    expect(database.rows.get(events[1].eventId)).toMatchObject({
      sync_status: 'pending',
      last_error: 'PUBLICATION_UNCONFIRMED',
    });
  });

  it.each([
    ['unknown ID', (events: readonly CapturedTelemetryEvent[]) => ({
      status: 207 as const,
      body: { results: [{ eventId: 'unknown', status: 'PUBLISHED' }] },
    })],
    ['duplicate IDs', (events: readonly CapturedTelemetryEvent[]) => ({
      status: 207 as const,
      body: { results: [
        { eventId: events[0].eventId, status: 'PUBLISHED' },
        { eventId: events[0].eventId, status: 'PUBLISHED' },
      ] },
    })],
    ['missing IDs', (events: readonly CapturedTelemetryEvent[]) => ({
      status: 207 as const,
      body: { results: [{ eventId: events[0].eventId, status: 'PUBLISHED' }] },
    })],
    ['unknown status', (events: readonly CapturedTelemetryEvent[]) => ({
      status: 207 as const,
      body: { results: events.map(({ eventId }) => ({ eventId, status: 'QUEUED' })) },
    })],
    ['202 without full PUBLISHED confirmation', (events: readonly CapturedTelemetryEvent[]) => ({
      status: 202 as const,
      body: { results: events.map(({ eventId }, index) => ({
        eventId,
        status: index === 0 ? 'PUBLICATION_UNCONFIRMED' : 'PUBLISHED',
        ...(index === 0 ? { errorCode: 'TELEMETRY_PUBLICATION_UNAVAILABLE' } : {}),
      })) },
    })],
    ['malformed body', () => ({ status: 207 as const, body: { results: 'invalid' } })],
    ['HTTP failure', () => { throw new TelemetryBatchClientError('HTTP', 503); }],
    ['network failure', () => { throw new TelemetryBatchClientError('NETWORK'); }],
    ['timeout', () => { throw new TelemetryBatchClientError('TIMEOUT'); }],
  ])('%s leaves events pending', async (_name, responseFactory) => {
    const database = new SyncDatabaseDouble();
    const events = [createEvent(2), createEvent(3)];
    events.forEach((event, index) => database.add(event, `2026-10-12T14:0${index}:00Z`));
    const postBatch = jest.fn(async (sent: readonly CapturedTelemetryEvent[]) => responseFactory(sent));

    const result = await createTelemetrySyncService({ postBatch })(database);

    expect(result.status).toBe('failed');
    for (const event of events) {
      expect(database.rows.get(event.eventId)).toMatchObject({ sync_status: 'pending', attempts: 1 });
    }
  });

  it('splits more than 100 events into bounded batches', async () => {
    const database = new SyncDatabaseDouble();
    const events = Array.from({ length: 101 }, (_, index) => createEvent(index));
    events.forEach((event, index) => database.add(event, new Date(Date.UTC(2026, 9, 12, 14, index)).toISOString()));
    const postBatch = jest.fn(async (sent: readonly CapturedTelemetryEvent[]) => publishedResponse(sent));
    const onProgress = jest.fn();

    const result = await createTelemetrySyncService({ postBatch })(database, onProgress);

    expect(postBatch.mock.calls.map(([batch]) => batch.length)).toEqual([100, 1]);
    expect(onProgress.mock.calls.map(([progress]) => progress.completedBatches)).toEqual([1, 2]);
    expect(result).toMatchObject({ status: 'completed', total: 101, confirmed: 101 });
  });

  it('preserves IDs and payload on a later manual retry', async () => {
    const database = new SyncDatabaseDouble();
    const event = createEvent(5);
    database.add(event, '2026-10-12T14:00:00Z');
    const postBatch = jest.fn()
      .mockRejectedValueOnce(Object.assign(new Error('offline'), { code: 'NETWORK' }))
      .mockImplementationOnce(async (sent: readonly CapturedTelemetryEvent[]) => publishedResponse(sent));
    const synchronize = createTelemetrySyncService({ postBatch });

    await synchronize(database);
    await synchronize(database);

    expect(postBatch.mock.calls[0][0][0]).toEqual(postBatch.mock.calls[1][0][0]);
    expect(database.rows.get(event.eventId)?.sync_status).toBe('synced');
  });

  it('rolls back every update when a SQLite transaction fails midway', async () => {
    const database = new SyncDatabaseDouble();
    const events = [createEvent(6), createEvent(7)];
    events.forEach((event, index) => database.add(event, `2026-10-12T14:0${index}:00Z`));
    database.failUpdateOnCall = 2;
    const postBatch = jest.fn(async (sent: readonly CapturedTelemetryEvent[]) => publishedResponse(sent));
    const synchronize = createTelemetrySyncService({ postBatch });

    await expect(synchronize(database)).rejects.toThrow('simulated local storage failure');
    for (const event of events) {
      expect(database.rows.get(event.eventId)).toMatchObject({ sync_status: 'pending', attempts: 0 });
    }
  });

  it('prevents concurrent synchronization runs', async () => {
    const database = new SyncDatabaseDouble();
    const event = createEvent(8);
    database.add(event, '2026-10-12T14:00:00Z');
    let releaseRequest: ((value: ReturnType<typeof publishedResponse>) => void) | undefined;
    const blockedPost = jest.fn(() => new Promise<ReturnType<typeof publishedResponse>>((resolve) => {
      releaseRequest = resolve;
    }));
    const concurrentSync = createTelemetrySyncService({ postBatch: blockedPost });
    const first = concurrentSync(database);
    await new Promise((resolve) => setTimeout(resolve, 0));
    const second = await concurrentSync(database);
    expect(second.status).toBe('busy');
    releaseRequest?.(publishedResponse([event]));
    expect((await first).status).toBe('completed');
  });
});
