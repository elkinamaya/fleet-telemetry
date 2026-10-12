import type { TelemetryDatabase } from '../storage/database';
import { listPendingTelemetryEvents, updateTelemetrySyncResults } from '../storage/telemetryStore';
import type { PendingTelemetryRecord, TelemetrySyncUpdate } from '../telemetry/types';
import {
  createTelemetryBatchClient,
  TELEMETRY_BATCH_MAX_EVENTS,
  TelemetryBatchClientError,
  type TelemetryBatchItemResult,
} from './telemetryBatchClient';

export type TelemetrySyncResult = Readonly<{
  status: 'completed' | 'partial' | 'failed' | 'busy';
  total: number;
  confirmed: number;
  pending: number;
  failureCode: string | null;
}>;

export type TelemetrySyncProgress = Readonly<{
  total: number;
  confirmed: number;
  pending: number;
  completedBatches: number;
  totalBatches: number;
}>;

type ParsedBatch = {
  updates: TelemetrySyncUpdate[];
  confirmed: number;
};

function sanitizeClientError(error: unknown): string {
  if (!(error instanceof TelemetryBatchClientError)) return 'SYNC_ERROR';
  switch (error.code) {
    case 'CONFIGURATION': return 'SYNC_CONFIGURATION_INVALID';
    case 'NETWORK': return 'NETWORK_ERROR';
    case 'TIMEOUT': return 'REQUEST_TIMEOUT';
    case 'HTTP':
      if (error.httpStatus === 400) return 'REQUEST_REJECTED';
      return `HTTP_${error.httpStatus ?? 'ERROR'}`;
    case 'INVALID_RESPONSE': return 'INVALID_BATCH_RESPONSE';
  }
}

function parseBatchResponse(
  responseStatus: number,
  body: unknown,
  events: readonly PendingTelemetryRecord[],
): ParsedBatch {
  if (!body || typeof body !== 'object' || !('results' in body)
    || !Array.isArray(body.results)) {
    throw new TelemetryBatchClientError('INVALID_RESPONSE', responseStatus);
  }

  const expectedIds = new Set(events.map(({ event }) => event.eventId));
  const seenIds = new Set<string>();
  const results = body.results as unknown[];
  if (results.length !== expectedIds.size) {
    throw new TelemetryBatchClientError('INVALID_RESPONSE', responseStatus);
  }

  const byId = new Map<string, TelemetryBatchItemResult>();
  for (const value of results) {
    if (!value || typeof value !== 'object') {
      throw new TelemetryBatchClientError('INVALID_RESPONSE', responseStatus);
    }
    const item = value as Record<string, unknown>;
    if (typeof item.eventId !== 'string' || !expectedIds.has(item.eventId)
      || seenIds.has(item.eventId) || typeof item.status !== 'string'
      || (item.errorCode !== undefined && item.errorCode !== null
        && typeof item.errorCode !== 'string')) {
      throw new TelemetryBatchClientError('INVALID_RESPONSE', responseStatus);
    }
    seenIds.add(item.eventId);
    byId.set(item.eventId, {
      eventId: item.eventId,
      status: item.status,
      errorCode: item.errorCode as string | null | undefined,
    });
  }

  if (seenIds.size !== expectedIds.size) {
    throw new TelemetryBatchClientError('INVALID_RESPONSE', responseStatus);
  }
  if (responseStatus !== 202 && responseStatus !== 207) {
    throw new TelemetryBatchClientError('INVALID_RESPONSE', responseStatus);
  }

  const updates = events.map(({ event }) => {
    const result = byId.get(event.eventId);
    if (!result) throw new TelemetryBatchClientError('INVALID_RESPONSE', responseStatus);

    if (result.status === 'PUBLISHED'
      && (result.errorCode === undefined || result.errorCode === null)) {
      return { eventId: event.eventId, status: 'synced', errorCode: null } as const;
    }
    if (responseStatus === 207
      && result.status === 'PUBLICATION_UNCONFIRMED'
      && result.errorCode === 'TELEMETRY_PUBLICATION_UNAVAILABLE') {
      return {
        eventId: event.eventId,
        status: 'pending',
        errorCode: 'PUBLICATION_UNCONFIRMED',
      } as const;
    }
    throw new TelemetryBatchClientError('INVALID_RESPONSE', responseStatus);
  });

  if (responseStatus === 202 && updates.some((update) => update.status !== 'synced')) {
    throw new TelemetryBatchClientError('INVALID_RESPONSE', responseStatus);
  }
  return {
    updates,
    confirmed: updates.filter((update) => update.status === 'synced').length,
  };
}

export function createTelemetrySyncService(options: {
  postBatch?: ReturnType<typeof createTelemetryBatchClient>;
} = {}) {
  const postBatch = options.postBatch ?? createTelemetryBatchClient();
  let inFlight = false;

  return async function synchronizePendingTelemetry(
    database: TelemetryDatabase,
    onProgress?: (progress: TelemetrySyncProgress) => void | Promise<void>,
  ): Promise<TelemetrySyncResult> {
    if (inFlight) {
      return { status: 'busy', total: 0, confirmed: 0, pending: 0, failureCode: null };
    }
    inFlight = true;

    try {
      const pending = await listPendingTelemetryEvents(database);
      let confirmed = 0;
      let failureCode: string | null = null;
      const totalBatches = Math.ceil(pending.length / TELEMETRY_BATCH_MAX_EVENTS);
      let completedBatches = 0;

      for (let offset = 0; offset < pending.length; offset += TELEMETRY_BATCH_MAX_EVENTS) {
        const batch = pending.slice(offset, offset + TELEMETRY_BATCH_MAX_EVENTS);
        let parsed: ParsedBatch;
        try {
          const response = await postBatch(batch.map(({ event }) => event));
          parsed = parseBatchResponse(response.status, response.body, batch);
        } catch (error) {
          failureCode = sanitizeClientError(error);
          await updateTelemetrySyncResults(database, batch.map(({ event }) => ({
            eventId: event.eventId,
            status: 'pending',
            errorCode: failureCode,
          })));
          completedBatches += 1;
          await onProgress?.({
            total: pending.length,
            confirmed,
            pending: pending.length - confirmed,
            completedBatches,
            totalBatches,
          });
          break;
        }
        await updateTelemetrySyncResults(database, parsed.updates);
        confirmed += parsed.confirmed;
        completedBatches += 1;
        await onProgress?.({
          total: pending.length,
          confirmed,
          pending: pending.length - confirmed,
          completedBatches,
          totalBatches,
        });
        if (parsed.confirmed < batch.length) {
          failureCode = 'PUBLICATION_UNCONFIRMED';
        }
      }

      const total = pending.length;
      const stillPending = total - confirmed;
      const status = stillPending === 0
        ? 'completed'
        : confirmed > 0
          ? 'partial'
          : 'failed';
      return {
        status,
        total,
        confirmed,
        pending: stillPending,
        failureCode,
      };
    } finally {
      inFlight = false;
    }
  };
}
