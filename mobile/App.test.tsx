import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import App from './App';
import type { TelemetrySyncProgress } from './src/sync/telemetrySyncService';

const mockOpenDatabaseAsync = jest.fn();
const mockInitializeDatabase = jest.fn();
const mockGetSummary = jest.fn();
const mockListEvents = jest.fn();
const mockSaveEvent = jest.fn();
const mockSynchronize = jest.fn();
const mockDatabase = {};
const mockRequestLocationPermission = jest.fn();
const mockGetCurrentPosition = jest.fn();

jest.mock('expo-sqlite', () => ({
  openDatabaseAsync: (...args: unknown[]) => mockOpenDatabaseAsync(...args),
}));
jest.mock('expo-status-bar', () => ({ StatusBar: () => null }));
jest.mock('react-native-safe-area-context', () => {
  const ReactNative = require('react-native');
  return {
    SafeAreaProvider: ({ children }: { children: React.ReactNode }) => children,
    SafeAreaView: ReactNative.View,
  };
});
jest.mock('expo-network', () => ({
  useNetworkState: () => ({ isConnected: true, isInternetReachable: true }),
}));
jest.mock('expo-location', () => ({
  requestForegroundPermissionsAsync: (...args: unknown[]) => mockRequestLocationPermission(...args),
  getCurrentPositionAsync: (...args: unknown[]) => mockGetCurrentPosition(...args),
  Accuracy: { Balanced: 3 },
}));
jest.mock('./src/storage/database', () => ({
  initializeTelemetryDatabase: (...args: unknown[]) => mockInitializeDatabase(...args),
}));
jest.mock('./src/storage/telemetryStore', () => ({
  getSyncSummary: (...args: unknown[]) => mockGetSummary(...args),
  listTelemetryEvents: (...args: unknown[]) => mockListEvents(...args),
  saveTelemetryEvent: (...args: unknown[]) => mockSaveEvent(...args),
}));
jest.mock('./src/sync/telemetrySyncService', () => ({
  createTelemetrySyncService: () => (...args: unknown[]) => mockSynchronize(...args),
}));

const pendingRecord = {
  event: {
    eventId: '1aa11111-1111-4111-8111-111111111111',
    vehicleId: 'TRUCK-TEST',
    latitude: 37.7,
    longitude: -122.4,
    timestamp: '2026-10-12T14:00:00Z',
    speed: null,
    source: 'manual_simulated',
  },
  status: 'pending',
  attempts: 0,
  createdAt: '2026-10-12T14:00:00Z',
  lastError: null,
};

describe('driver sync interface', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockOpenDatabaseAsync.mockResolvedValue(mockDatabase);
    mockInitializeDatabase.mockResolvedValue(undefined);
    mockRequestLocationPermission.mockResolvedValue({ granted: true });
    mockGetCurrentPosition.mockResolvedValue({
      coords: { latitude: 37.7, longitude: -122.4, speed: 10 },
    });
    mockGetSummary
      .mockResolvedValueOnce({ pending: 1, synced: 0 })
      .mockResolvedValue({ pending: 0, synced: 1 });
    mockListEvents
      .mockResolvedValueOnce([pendingRecord])
      .mockResolvedValue([{
        ...pendingRecord,
        status: 'synced',
      }]);
    mockSynchronize.mockImplementation(async (
      _database: unknown,
      onProgress?: (progress: TelemetrySyncProgress) => void | Promise<void>,
    ) => {
      await onProgress?.({
        total: 1,
        confirmed: 1,
        pending: 0,
        completedBatches: 1,
        totalBatches: 1,
      });
      return { status: 'completed', total: 1, confirmed: 1, pending: 0, failureCode: null };
    });
  });

  it('connects the manual sync action and refreshes confirmed local status', async () => {
    const ui = await render(<App />);
    await waitFor(() => expect(ui.getByText('Sincronizar ahora')).toBeTruthy());

    await act(async () => {
      fireEvent.press(ui.getByText('Sincronizar ahora'));
    });

    await waitFor(() => {
      expect(mockSynchronize).toHaveBeenCalledWith(mockDatabase, expect.any(Function));
      expect(ui.getByText('PUBLISHED · KAFKA')).toBeTruthy();
    });
    expect(ui.getByText('Simulada · coordenadas de prueba')).toBeTruthy();
  });

  it('stores GPS speed converted from meters per second to kilometers per hour', async () => {
    const ui = await render(<App />);
    await waitFor(() => expect(ui.getByText('Capturar ubicación GPS')).toBeTruthy());

    await act(async () => {
      fireEvent.press(ui.getByText('Capturar ubicación GPS'));
    });

    await waitFor(() => expect(mockSaveEvent).toHaveBeenCalledWith(
      mockDatabase,
      expect.objectContaining({ speed: 36, source: 'gps' }),
    ));
  });

  it('stores GPS speed as null when Expo Location has no measurement', async () => {
    mockGetCurrentPosition.mockResolvedValueOnce({
      coords: { latitude: 37.7, longitude: -122.4, speed: null },
    });
    const ui = await render(<App />);
    await waitFor(() => expect(ui.getByText('Capturar ubicación GPS')).toBeTruthy());

    await act(async () => {
      fireEvent.press(ui.getByText('Capturar ubicación GPS'));
    });

    await waitFor(() => expect(mockSaveEvent).toHaveBeenCalledWith(
      mockDatabase,
      expect.objectContaining({ speed: null, source: 'gps' }),
    ));
  });

  it.each([
    ['0', 0],
    ['52.25', 52.25],
    ['', null],
  ])('stores manual speed input %s as %s km/h', async (speedInput, expectedSpeed) => {
    const ui = await render(<App />);
    await waitFor(() => expect(ui.getByText('Guardar coordenadas simuladas')).toBeTruthy());

    await act(async () => {
      fireEvent.changeText(ui.getByLabelText('Velocidad simulada en km/h'), speedInput);
    });
    await act(async () => {
      fireEvent.press(ui.getByText('Guardar coordenadas simuladas'));
    });

    await waitFor(() => expect(mockSaveEvent).toHaveBeenCalledWith(
      mockDatabase,
      expect.objectContaining({ speed: expectedSpeed, source: 'manual_simulated' }),
    ));
  });

  it.each(['rápido', '-2', 'Infinity'])(
    'rejects invalid manual speed %s without saving an event',
    async (speedInput) => {
      const ui = await render(<App />);
      await waitFor(() => expect(ui.getByText('Guardar coordenadas simuladas')).toBeTruthy());

      await act(async () => {
        fireEvent.changeText(ui.getByLabelText('Velocidad simulada en km/h'), speedInput);
      });
      await act(async () => {
        fireEvent.press(ui.getByText('Guardar coordenadas simuladas'));
      });

      expect(await ui.findByText(
        'Introduce una velocidad simulada válida, igual o mayor que 0 km/h.',
      )).toBeTruthy();
      expect(mockSaveEvent).not.toHaveBeenCalled();
    },
  );
});
