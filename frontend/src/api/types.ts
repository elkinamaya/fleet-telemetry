export interface TelemetryEvent {
  eventId: string
  vehicleId: string
  latitude: number
  longitude: number
  timestamp: string
  speed: number | null
}

export interface VehicleAlert {
  alertId: string
  vehicleId: string
  type: 'STOPPED' | 'SPEEDING' | 'NO_RECENT_REPORTS'
  description: string
  detectedAt: string
  updatedAt: string
  speed: number | null
  status: 'ACTIVE' | 'RESOLVED'
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
    !Number.isFinite(Date.parse(event.timestamp)) ||
    (event.speed !== undefined &&
      event.speed !== null &&
      (typeof event.speed !== 'number' || !Number.isFinite(event.speed) || event.speed < 0))
  ) {
    return null
  }

  return {
    eventId: event.eventId,
    vehicleId: event.vehicleId,
    latitude: event.latitude,
    longitude: event.longitude,
    timestamp: event.timestamp,
    speed: typeof event.speed === 'number' ? event.speed : null,
  }
}

export function parseVehicleAlert(value: unknown): VehicleAlert | null {
  if (!isRecord(value)) return null
  if (
    typeof value.alertId !== 'string' ||
    typeof value.vehicleId !== 'string' ||
    (value.type !== 'STOPPED' &&
      value.type !== 'SPEEDING' &&
      value.type !== 'NO_RECENT_REPORTS') ||
    typeof value.description !== 'string' ||
    typeof value.detectedAt !== 'string' ||
    !Number.isFinite(Date.parse(value.detectedAt)) ||
    typeof value.updatedAt !== 'string' ||
    !Number.isFinite(Date.parse(value.updatedAt)) ||
    (value.status !== 'ACTIVE' && value.status !== 'RESOLVED') ||
    (value.speed !== undefined &&
      value.speed !== null &&
      (typeof value.speed !== 'number' ||
        !Number.isFinite(value.speed) ||
        value.speed < 0))
  ) {
    return null
  }

  return {
    alertId: value.alertId,
    vehicleId: value.vehicleId,
    type: value.type,
    description: value.description,
    detectedAt: value.detectedAt,
    updatedAt: value.updatedAt,
    speed: typeof value.speed === 'number' ? value.speed : null,
    status: value.status,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
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
