import type { SQLiteBindValue, SQLiteRunResult } from 'expo-sqlite';

export interface TelemetryDatabase {
  execAsync(source: string): Promise<void>;
  runAsync(source: string, ...params: SQLiteBindValue[]): Promise<SQLiteRunResult>;
  getAllAsync<T>(source: string, ...params: SQLiteBindValue[]): Promise<T[]>;
  getFirstAsync<T>(source: string, ...params: SQLiteBindValue[]): Promise<T | null>;
  withExclusiveTransactionAsync(task: (transaction: TelemetryTransaction) => Promise<void>): Promise<void>;
}

export interface TelemetryTransaction {
  runAsync(source: string, ...params: SQLiteBindValue[]): Promise<SQLiteRunResult>;
}

export async function initializeTelemetryDatabase(database: TelemetryDatabase): Promise<void> {
  await database.execAsync(`
    PRAGMA journal_mode = WAL;
    CREATE TABLE IF NOT EXISTS telemetry_events (
      event_id TEXT PRIMARY KEY NOT NULL,
      vehicle_id TEXT NOT NULL CHECK (length(trim(vehicle_id)) BETWEEN 1 AND 100),
      latitude REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
      longitude REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
      timestamp TEXT NOT NULL,
      speed REAL CHECK (speed IS NULL OR speed >= 0),
      source TEXT NOT NULL CHECK (source IN ('gps', 'manual_simulated')),
      sync_status TEXT NOT NULL DEFAULT 'pending' CHECK (sync_status IN ('pending', 'synced')),
      attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
      created_at TEXT NOT NULL,
      last_error TEXT
    );
    CREATE INDEX IF NOT EXISTS idx_telemetry_events_sync_status
      ON telemetry_events(sync_status, created_at);
  `);
}
