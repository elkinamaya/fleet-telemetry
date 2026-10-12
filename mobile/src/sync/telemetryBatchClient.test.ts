import { createTelemetryEvent } from '../telemetry/createTelemetryEvent';
import { createTelemetryBatchClient } from './telemetryBatchClient';

let mockUuidCounter = 0;
jest.mock('expo-crypto', () => ({
  randomUUID: jest.fn(() => `22222222-2222-4222-8222-${String(++mockUuidCounter).padStart(12, '0')}`),
}));

function event(speed?: number) {
  return createTelemetryEvent({
    vehicleId: 'TRUCK-1',
    latitude: 37.7,
    longitude: -122.4,
    ...(speed === undefined ? {} : { speed }),
  }, 'manual_simulated');
}

describe('telemetry batch HTTP client', () => {
  it('posts only backend DTO fields and preserves event identifiers and payload', async () => {
    const fetcher = jest.fn().mockResolvedValue({
      ok: true,
      status: 202,
      json: async () => ({ results: [] }),
    });
    const captured = event(45);
    const client = createTelemetryBatchClient({
      baseUrl: 'http://127.0.0.1:8080/',
      fetcher,
    });

    await client([captured]);

    const [url, init] = fetcher.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://127.0.0.1:8080/api/telemetry/batch');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({
      events: [{
        eventId: captured.eventId,
        vehicleId: captured.vehicleId,
        latitude: captured.latitude,
        longitude: captured.longitude,
        timestamp: captured.timestamp,
        speed: 45,
      }],
    });
    expect(String(init.body)).not.toContain('source');
  });

  it.each([
    [0, 0],
    [45, 45],
  ])('includes numeric speed %s in the batch payload', async (speed, expectedSpeed) => {
    const fetcher = jest.fn().mockResolvedValue({
      ok: true,
      status: 202,
      json: async () => ({ results: [] }),
    });
    const captured = event(speed);
    await createTelemetryBatchClient({ baseUrl: 'http://localhost:8080', fetcher })([captured]);
    const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body)).events[0]).toMatchObject({ speed: expectedSpeed });
    expect(JSON.parse(String(init.body)).events[0]).not.toHaveProperty('source');
  });

  it('omits speed when it is not available', async () => {
    const fetcher = jest.fn().mockResolvedValue({
      ok: true,
      status: 202,
      json: async () => ({ results: [] }),
    });
    const captured = event();
    await createTelemetryBatchClient({ baseUrl: 'http://localhost:8080', fetcher })([captured]);
    const [, init] = fetcher.mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body)).events[0]).not.toHaveProperty('speed');
  });

  it('reports invalid configuration without sending a request', async () => {
    const fetcher = jest.fn();
    const client = createTelemetryBatchClient({ baseUrl: 'not a URL', fetcher });
    await expect(client([event()])).rejects.toMatchObject({ code: 'CONFIGURATION' });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('rejects batches over the backend limit without sending a request', async () => {
    const fetcher = jest.fn();
    const client = createTelemetryBatchClient({ baseUrl: 'http://localhost:8080', fetcher });
    await expect(client(Array.from({ length: 101 }, () => event()))).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
    });
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('classifies timeout and HTTP failures without retaining response bodies', async () => {
    const timeoutFetcher = jest.fn((_url: string, init?: RequestInit) => new Promise<never>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => {
        const timeout = new Error('aborted');
        timeout.name = 'AbortError';
        reject(timeout);
      });
    }));
    const timeoutClient = createTelemetryBatchClient({
      baseUrl: 'http://localhost:8080',
      fetcher: timeoutFetcher,
      timeoutMs: 5,
    });
    await expect(timeoutClient([event()])).rejects.toMatchObject({ code: 'TIMEOUT' });

    const httpClient = createTelemetryBatchClient({
      baseUrl: 'http://localhost:8080',
      fetcher: jest.fn().mockResolvedValue({
        ok: false,
        status: 503,
        json: async () => ({ secret: 'never persisted' }),
      }),
    });
    await expect(httpClient([event()])).rejects.toMatchObject({ code: 'HTTP', httpStatus: 503 });
  });

  it('rejects malformed JSON responses', async () => {
    const client = createTelemetryBatchClient({
      baseUrl: 'http://localhost:8080',
      fetcher: jest.fn().mockResolvedValue({
        ok: true,
        status: 202,
        json: async () => { throw new Error('malformed'); },
      }),
    });
    await expect(client([event()])).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });
});
