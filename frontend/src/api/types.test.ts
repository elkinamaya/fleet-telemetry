import { describe, expect, it } from 'vitest'
import { hasValidCoordinates, parseTelemetryEvent } from './types'

const event = {
  eventId: 'e4c87b0b-1426-446d-9588-164c11eaa327',
  vehicleId: 'truck-17',
  latitude: 4.711,
  longitude: -74.0721,
  timestamp: '2026-10-09T15:00:00Z',
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
  })

  it('does not map missing or out-of-range coordinates', () => {
    expect(hasValidCoordinates({ ...event, latitude: 91 })).toBe(false)
    expect(hasValidCoordinates({ ...event, longitude: -181 })).toBe(false)
    expect(hasValidCoordinates({ ...event, latitude: Number.NaN })).toBe(false)
  })
})
