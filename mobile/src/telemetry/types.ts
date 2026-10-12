export type CaptureSource = 'gps' | 'manual_simulated';
export type SyncStatus = 'pending' | 'synced';

export type CapturedTelemetryEvent = Readonly<{
  eventId: string;
  vehicleId: string;
  latitude: number;
  longitude: number;
  timestamp: string;
  speed: number | null;
  source: CaptureSource;
}>;

export type PendingTelemetryRecord = Readonly<{
  event: CapturedTelemetryEvent;
  status: SyncStatus;
  attempts: number;
  createdAt: string;
  lastError: string | null;
}>;

export type SyncSummary = Readonly<{
  pending: number;
  synced: number;
}>;

export type TelemetryRecord = PendingTelemetryRecord;

export type TelemetrySyncUpdate = Readonly<{
  eventId: string;
  status: 'synced' | 'pending';
  errorCode: string | null;
}>;
