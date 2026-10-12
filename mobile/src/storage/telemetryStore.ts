import type {
  CapturedTelemetryEvent,
  PendingTelemetryRecord,
  SyncSummary,
  SyncStatus,
  TelemetryRecord,
  TelemetrySyncUpdate,
} from '../telemetry/types';
import type { TelemetryDatabase } from './database';

type TelemetryRow = {
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

type SummaryRow = {
  pending: number;
  synced: number;
};

function toRecord(row: TelemetryRow): PendingTelemetryRecord {
  return Object.freeze({
    event: Object.freeze({
      eventId: row.event_id,
      vehicleId: row.vehicle_id,
      latitude: row.latitude,
      longitude: row.longitude,
      timestamp: row.timestamp,
      speed: row.speed,
      source: row.source,
    }),
    attempts: row.attempts,
    createdAt: row.created_at,
    lastError: row.last_error,
    status: row.sync_status,
  });
}

export async function saveTelemetryEvent(
  database: TelemetryDatabase,
  event: CapturedTelemetryEvent,
): Promise<void> {
  const createdAt = new Date().toISOString();
  await database.runAsync(
    `INSERT INTO telemetry_events
      (event_id, vehicle_id, latitude, longitude, timestamp, speed, source, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    event.eventId,
    event.vehicleId,
    event.latitude,
    event.longitude,
    event.timestamp,
    event.speed,
    event.source,
    createdAt,
  );
}

export async function listPendingTelemetryEvents(
  database: TelemetryDatabase,
): Promise<PendingTelemetryRecord[]> {
  const rows = await database.getAllAsync<TelemetryRow>(
    `SELECT event_id, vehicle_id, latitude, longitude, timestamp, speed, source,
            sync_status, attempts, created_at, last_error
     FROM telemetry_events
     WHERE sync_status = 'pending'
     ORDER BY created_at ASC, rowid ASC`,
  );
  return rows.map(toRecord);
}

export async function listTelemetryEvents(
  database: TelemetryDatabase,
): Promise<TelemetryRecord[]> {
  const rows = await database.getAllAsync<TelemetryRow>(
    `SELECT event_id, vehicle_id, latitude, longitude, timestamp, speed, source,
            sync_status, attempts, created_at, last_error
     FROM telemetry_events
     ORDER BY created_at DESC`,
  );
  return rows.map((row) => toRecord(row));
}

export async function updateTelemetrySyncResults(
  database: TelemetryDatabase,
  updates: readonly TelemetrySyncUpdate[],
): Promise<void> {
  if (updates.length === 0) return;
  await database.withExclusiveTransactionAsync(async (transaction) => {
    for (const update of updates) {
      await transaction.runAsync(
        `UPDATE telemetry_events
         SET sync_status = ?, attempts = attempts + 1, last_error = ?
         WHERE event_id = ? AND sync_status = 'pending'`,
        update.status,
        update.errorCode,
        update.eventId,
      );
    }
  });
}

export async function getSyncSummary(database: TelemetryDatabase): Promise<SyncSummary> {
  const row = await database.getFirstAsync<SummaryRow>(
    `SELECT
       COALESCE(SUM(CASE WHEN sync_status = 'pending' THEN 1 ELSE 0 END), 0) AS pending,
       COALESCE(SUM(CASE WHEN sync_status = 'synced' THEN 1 ELSE 0 END), 0) AS synced
     FROM telemetry_events`,
  );
  if (!row) throw new Error('No se pudo consultar el resumen de telemetría local.');
  return Object.freeze({ pending: row.pending, synced: row.synced });
}
