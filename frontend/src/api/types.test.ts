import { describe, expect, it } from 'vitest'
import { hasValidCoordinates, parseTelemetryEvent, parseVehicleAlert } from './types'

const event = {
  eventId: 'e4c87b0b-1426-446d-9588-164c11eaa327',
  vehicleId: 'truck-17',
  latitude: 4.711,
  longitude: -74.0721,
  timestamp: '2026-10-09T15:00:00Z',
  speed: 64.5,
}

describe('telemetry API contracts', () => {
  it('accepts a valid telemetry event and valid coordinates', () => {
    expect(parseTelemetryEvent(event)).toEqual(event)
    expect(hasValidCoordinates(event)).toBe(true)
  })

  it('rejects incomplete or malformed event data', () => {
    expect(parseTelemetryEvent({ ...event, timestamp: 'not-a-date' })).toBeNull()
    expect(parseTelemetryEvent({ ...event, longitude: undefined })).toBeNull()
    expect(parseTelemetryEvent(null)).toBeNull()
    expect(parseTelemetryEvent({ ...event, speed: -1 })).toBeNull()
    expect(parseTelemetryEvent({ ...event, speed: Number.POSITIVE_INFINITY })).toBeNull()
  })

  it('accepts legacy telemetry without speed as unknown', () => {
    expect(parseTelemetryEvent({ ...event, speed: undefined })?.speed).toBeNull()
  })

  it('validates active alert payloads', () => {
    const alert = {
      alertId: '35c5ac65-390f-45e8-8a8b-a3c6ab2add5f',
      vehicleId: 'truck-17',
      type: 'SPEEDING',
      description: 'Velocidad excesiva.',
      detectedAt: '2026-10-09T15:00:00Z',
      updatedAt: '2026-10-09T15:00:00Z',
      speed: 85,
      status: 'ACTIVE',
    }

    expect(parseVehicleAlert(alert)).toEqual(alert)
    expect(parseVehicleAlert({ ...alert, status: 'OTHER' })).toBeNull()
  })

  it('does not map missing or out-of-range coordinates', () => {
    expect(hasValidCoordinates({ ...event, latitude: 91 })).toBe(false)
    expect(hasValidCoordinates({ ...event, longitude: -181 })).toBe(false)
    expect(hasValidCoordinates({ ...event, latitude: Number.NaN })).toBe(false)
  })
})
