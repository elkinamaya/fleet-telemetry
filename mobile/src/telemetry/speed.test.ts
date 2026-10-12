import { gpsSpeedMpsToKmh, parseManualSpeedKmh } from './speed';

describe('telemetry speed conversion and validation', () => {
  it('converts GPS speed from meters per second to kilometers per hour without rounding', () => {
    expect(gpsSpeedMpsToKmh(10)).toBe(36);
    expect(gpsSpeedMpsToKmh(1.25)).toBe(4.5);
  });

  it.each([null, -1, Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY])(
    'treats unavailable or invalid GPS speed %s as null',
    (speedMps) => {
      expect(gpsSpeedMpsToKmh(speedMps)).toBeNull();
    },
  );

  it('treats an overflowed GPS conversion as unavailable', () => {
    expect(gpsSpeedMpsToKmh(Number.MAX_VALUE)).toBeNull();
  });

  it('accepts zero and positive simulated speed values in kilometers per hour', () => {
    expect(parseManualSpeedKmh('0')).toBe(0);
    expect(parseManualSpeedKmh('45.5')).toBe(45.5);
  });

  it('treats an empty manual speed as unavailable', () => {
    expect(parseManualSpeedKmh('  ')).toBeNull();
  });

  it.each(['fast', '-1', 'Infinity', 'NaN'])(
    'rejects invalid manual speed input %s',
    (value) => {
      expect(() => parseManualSpeedKmh(value)).toThrow(
        'Introduce una velocidad simulada válida, igual o mayor que 0 km/h.',
      );
    },
  );
});
