import type { CapturedTelemetryEvent } from '../telemetry/types';

export const TELEMETRY_BATCH_MAX_EVENTS = 100;
export const TELEMETRY_BATCH_TIMEOUT_MS = 10_000;

export type TelemetryBatchPayload = Readonly<{
  eventId: string;
  vehicleId: string;
  latitude: number;
  longitude: number;
  timestamp: string;
  speed?: number;
}>;

export type TelemetryBatchItemResult = Readonly<{
  eventId: string;
  status: string;
  errorCode?: string | null;
}>;

export type TelemetryBatchResponse = Readonly<{
  results: TelemetryBatchItemResult[];
}>;

export class TelemetryBatchClientError extends Error {
  constructor(
    readonly code: 'CONFIGURATION' | 'NETWORK' | 'TIMEOUT' | 'HTTP' | 'INVALID_RESPONSE',
    readonly httpStatus?: number,
  ) {
    super(code);
    this.name = 'TelemetryBatchClientError';
  }
}

type FetchResponse = Pick<Response, 'ok' | 'status' | 'json'>;
type FetchImplementation = (input: string, init?: RequestInit) => Promise<FetchResponse>;

function getBatchUrl(baseUrl: string | undefined): string {
  if (!baseUrl?.trim()) throw new TelemetryBatchClientError('CONFIGURATION');

  let parsed: URL;
  try {
    parsed = new URL(baseUrl.trim());
  } catch {
    throw new TelemetryBatchClientError('CONFIGURATION');
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new TelemetryBatchClientError('CONFIGURATION');
  }
  if (parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new TelemetryBatchClientError('CONFIGURATION');
  }

  return `${parsed.toString().replace(/\/+$/, '')}/api/telemetry/batch`;
}

export function toTelemetryBatchPayload(event: CapturedTelemetryEvent): TelemetryBatchPayload {
  return Object.freeze({
    eventId: event.eventId,
    vehicleId: event.vehicleId,
    latitude: event.latitude,
    longitude: event.longitude,
    timestamp: event.timestamp,
    ...(event.speed === null ? {} : { speed: event.speed }),
  });
}

export function createTelemetryBatchClient(options: {
  baseUrl?: string;
  fetcher?: FetchImplementation;
  timeoutMs?: number;
} = {}) {
  const fetcher = options.fetcher ?? fetch;
  const timeoutMs = options.timeoutMs ?? TELEMETRY_BATCH_TIMEOUT_MS;

  return async function postTelemetryBatch(
    events: readonly CapturedTelemetryEvent[],
  ): Promise<{ status: 202 | 207; body: unknown }> {
    if (events.length === 0 || events.length > TELEMETRY_BATCH_MAX_EVENTS) {
      throw new TelemetryBatchClientError('INVALID_RESPONSE');
    }
    const url = getBatchUrl(options.baseUrl ?? process.env.EXPO_PUBLIC_API_BASE_URL);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);

    try {
      let response: FetchResponse;
      try {
        response = await fetcher(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          body: JSON.stringify({ events: events.map(toTelemetryBatchPayload) }),
          signal: controller.signal,
        });
      } catch (error) {
        if (controller.signal.aborted || (error instanceof Error && error.name === 'AbortError')) {
          throw new TelemetryBatchClientError('TIMEOUT');
        }
        throw new TelemetryBatchClientError('NETWORK');
      }

      if (response.status !== 202 && response.status !== 207) {
        throw new TelemetryBatchClientError('HTTP', response.status);
      }
      let body: unknown;
      try {
        body = await response.json();
      } catch {
        throw new TelemetryBatchClientError('INVALID_RESPONSE', response.status);
      }
      if (!response.ok || (response.status !== 202 && response.status !== 207)) {
        throw new TelemetryBatchClientError('HTTP', response.status);
      }
      return {
        status: response.status,
        body,
      };
    } finally {
      clearTimeout(timeout);
    }
  };
}
