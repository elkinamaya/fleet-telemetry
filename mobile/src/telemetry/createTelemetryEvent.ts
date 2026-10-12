import * as Crypto from 'expo-crypto';
import type { CaptureSource, CapturedTelemetryEvent } from './types';

export function createTelemetryEvent(
  input: {
    vehicleId: string;
    latitude: number;
    longitude: number;
    speed?: number;
  },
  source: CaptureSource,
): CapturedTelemetryEvent {
  const vehicleId = input.vehicleId.trim();
  if (!vehicleId || vehicleId.length > 100) {
    throw new Error('El identificador del vehículo debe tener entre 1 y 100 caracteres.');
  }
  if (!Number.isFinite(input.latitude) || input.latitude < -90 || input.latitude > 90) {
    throw new Error('La latitud debe estar entre -90 y 90.');
  }
  if (!Number.isFinite(input.longitude) || input.longitude < -180 || input.longitude > 180) {
    throw new Error('La longitud debe estar entre -180 y 180.');
  }
  if (input.speed !== undefined && (!Number.isFinite(input.speed) || input.speed < 0)) {
    throw new Error('La velocidad debe ser un número no negativo.');
  }

  const timestamp = new Date().toISOString();
  return Object.freeze({
    eventId: Crypto.randomUUID(),
    vehicleId,
    latitude: input.latitude,
    longitude: input.longitude,
    timestamp,
    speed: input.speed ?? null,
    source,
  });
}
