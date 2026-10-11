import {
  parseTelemetryEvent,
  parseVehicleAlert,
  type AiChatResponse,
  type ProblemResponse,
  type TelemetryEvent,
  type VehicleAlert,
} from './types'

const apiBaseUrl = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/+$/, '')

export class ApiError extends Error {
  readonly status: number
  readonly code?: string

  constructor(
    message: string,
    status: number,
    code?: string,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${apiBaseUrl}${path}`, init)
  } catch {
    throw new ApiError('No se pudo conectar con el backend.', 0)
  }

  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as ProblemResponse
    throw new ApiError(
      problem.detail ?? problem.title ?? 'La solicitud no pudo completarse.',
      response.status,
      problem.code,
    )
  }
  return (await response.json()) as T
}

export async function getVehicleState(vehicleId: string): Promise<TelemetryEvent> {
  const response: unknown = await request<unknown>(
    `/vehicles/${encodeURIComponent(vehicleId)}/state`,
  )
  const event = parseTelemetryEvent(response)
  if (!event) {
    throw new ApiError('El backend devolvió un estado de vehículo inválido.', 502)
  }
  return event
}

export async function getActiveAlerts(): Promise<VehicleAlert[]> {
  const response: unknown = await request<unknown>('/alerts')
  if (!Array.isArray(response)) {
    throw new ApiError('El backend devolvió una lista de alertas inválida.', 502)
  }
  const alerts: VehicleAlert[] = []
  for (const value of response) {
    const alert = parseVehicleAlert(value)
    if (!alert) {
      throw new ApiError('El backend devolvió una alerta inválida.', 502)
    }
    alerts.push(alert)
  }
  return alerts
}

export async function askAgent(question: string): Promise<AiChatResponse> {
  const response: unknown = await request<unknown>('/ai/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question }),
  })
  const payload = isRecord(response) ? response : null
  const toolsUsed = payload?.toolsUsed
  if (
    !payload ||
    typeof payload.answer !== 'string' ||
    !Array.isArray(toolsUsed) ||
    !toolsUsed.every((tool: unknown) => typeof tool === 'string')
  ) {
    throw new ApiError('El backend devolvió una respuesta del asistente inválida.', 502)
  }
  return { answer: payload.answer, toolsUsed }
}

export function getTelemetryStreamUrl(): string {
  return `${apiBaseUrl}/telemetry/stream`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
