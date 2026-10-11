export interface TelemetryEvent {
  eventId: string
  vehicleId: string
  latitude: number
  longitude: number
  timestamp: string
}

export interface AiChatResponse {
  answer: string
  toolsUsed: string[]
}

export interface ProblemResponse {
  code?: string
  detail?: string
  title?: string
  status?: number
}

export type BackendConnection = 'unknown' | 'connected' | 'disconnected'

export function parseTelemetryEvent(value: unknown): TelemetryEvent | null {
  if (typeof value !== 'object' || value === null) return null

  const event = value as Record<string, unknown>
  if (
    typeof event.eventId !== 'string' ||
    typeof event.vehicleId !== 'string' ||
    typeof event.latitude !== 'number' ||
    typeof event.longitude !== 'number' ||
    typeof event.timestamp !== 'string' ||
    !Number.isFinite(Date.parse(event.timestamp))
  ) {
    return null
  }

  return {
    eventId: event.eventId,
    vehicleId: event.vehicleId,
    latitude: event.latitude,
    longitude: event.longitude,
    timestamp: event.timestamp,
  }
}

export function hasValidCoordinates(event: TelemetryEvent): boolean {
  return (
    Number.isFinite(event.latitude) &&
    Number.isFinite(event.longitude) &&
    event.latitude >= -90 &&
    event.latitude <= 90 &&
    event.longitude >= -180 &&
    event.longitude <= 180
  )
}
