export function gpsSpeedMpsToKmh(speedMps: number | null): number | null {
  if (speedMps === null || !Number.isFinite(speedMps) || speedMps < 0) return null;
  const speedKmh = speedMps * 3.6;
  return Number.isFinite(speedKmh) ? speedKmh : null;
}

export function parseManualSpeedKmh(value: string): number | null {
  const normalizedValue = value.trim();
  if (!normalizedValue) return null;

  const speedKmh = Number(normalizedValue);
  if (!Number.isFinite(speedKmh) || speedKmh < 0) {
    throw new Error('Introduce una velocidad simulada válida, igual o mayor que 0 km/h.');
  }
  return speedKmh;
}
