import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TelemetryEvent } from './api/types'
import App from './App'

const event = {
  eventId: 'e4c87b0b-1426-446d-9588-164c11eaa327',
  vehicleId: 'truck-17',
  latitude: 4.711,
  longitude: -74.0721,
  timestamp: '2026-10-09T15:00:00Z',
  speed: 56.5,
}

const alert = {
  alertId: '35c5ac65-390f-45e8-8a8b-a3c6ab2add5f',
  vehicleId: 'truck-17',
  type: 'SPEEDING',
  description: 'Velocidad superior a 80 km/h en dos muestras consecutivas.',
  detectedAt: '2026-10-09T15:00:00Z',
  updatedAt: '2026-10-09T15:00:00Z',
  speed: 85,
  status: 'ACTIVE',
}

const mapMock = vi.hoisted(() => vi.fn())
vi.mock('./components/FleetMap', () => ({
  default: (props: { vehicles: TelemetryEvent[] }) => {
    mapMock(props)
    const validVehicles = props.vehicles.filter(
      (vehicle) =>
        Number.isFinite(vehicle.latitude) &&
        Number.isFinite(vehicle.longitude) &&
        vehicle.latitude >= -90 &&
        vehicle.latitude <= 90 &&
        vehicle.longitude >= -180 &&
        vehicle.longitude <= 180,
    )
    if (validVehicles.length === 0) {
      return <div data-testid="fleet-map">No hay posiciones para mostrar</div>
    }
    return <div data-testid="fleet-map">
      {validVehicles.map((vehicle) => (
        <span key={vehicle.vehicleId}>{vehicle.vehicleId}</span>
      ))}
    </div>
  },
}))

class MockEventSource {
  static instances: MockEventSource[] = []
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  closed = false
  listeners = new Map<string, (event: MessageEvent<string>) => void>()
  readonly url: string

  constructor(url: string) {
    this.url = url
    MockEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void) {
    this.listeners.set(type, listener)
  }

  close() {
    this.closed = true
  }

  emit(type: string, data: unknown) {
    this.listeners.get(type)?.({ data: JSON.stringify(data) } as MessageEvent<string>)
  }
}

function mockFetch(response: Response | Promise<Response>) {
  return vi.fn(() => Promise.resolve(response).then((value) => value.clone()))
}

describe('fleet dashboard', () => {
  beforeEach(() => {
    MockEventSource.instances = []
    mapMock.mockClear()
    vi.stubGlobal('EventSource', MockEventSource)
    vi.stubGlobal('fetch', mockFetch(new Response(JSON.stringify([]), { status: 200 })))
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders an honest empty state before any vehicle is discovered', () => {
    render(<App />)
    expect(screen.getByText('No hay posiciones para mostrar')).toBeInTheDocument()
    expect(screen.getByText(/El backend no ofrece un listado inicial/)).toBeInTheDocument()
    expect(screen.getByTestId('vehicle-total')).toHaveTextContent('0')
    expect(screen.getByTestId('position-total')).toHaveTextContent('0')
    expect(MockEventSource.instances).toHaveLength(1)
  })

  it('places connection indicators between the header and page heading', () => {
    const { container } = render(<App />)
    const appShell = container.querySelector('.app-shell')

    expect(appShell?.children[0]).toHaveClass('topbar')
    expect(appShell?.children[1]).toHaveClass('topbar-right')
    expect(appShell?.children[2]).toHaveClass('page-heading')
  })

  it('discovers valid SSE positions and ignores out-of-range coordinates', async () => {
    render(<App />)
    const source = MockEventSource.instances[0]
    source.emit('telemetry', event)
    source.emit('telemetry', { ...event, latitude: 6, timestamp: '2026-10-09T16:00:00Z' })
    source.emit('telemetry', { ...event, eventId: 'newer', latitude: 5, timestamp: '2026-10-09T16:00:00Z' })
    source.emit('telemetry', { ...event, eventId: 'older', latitude: 3 })
    source.emit('telemetry', { ...event, eventId: 'bad-position', vehicleId: 'truck-18', latitude: 95 })

    await waitFor(() => expect(screen.getAllByText('truck-17')).toHaveLength(2))
    expect(screen.getByText('5.000000')).toBeInTheDocument()
    expect(screen.queryByText('truck-18')).not.toBeInTheDocument()
    expect(screen.getByText('1 posiciones')).toBeInTheDocument()
  })

  it('shows new telemetry temporarily and updates active operational alerts from SSE', async () => {
    render(<App />)
    const source = MockEventSource.instances[0]

    source.emit('telemetry', event)
    source.emit('telemetry', { ...event, vehicleId: 'duplicate-vehicle' })
    expect(await screen.findByRole('status')).toHaveTextContent(/truck-17 reportó telemetría/)
    expect(screen.getByTestId('vehicle-total')).toHaveTextContent('1')

    source.emit('alert', alert)
    expect(await screen.findByText('Velocidad excesiva')).toBeInTheDocument()
    expect(screen.getByText('1', { selector: '.muted-count' })).toBeInTheDocument()

    source.emit('alert', { ...alert, status: 'RESOLVED' })
    await waitFor(() => expect(screen.getByText('0', { selector: '.muted-count' })).toBeInTheDocument())
  })

  it('loads the active alert snapshot when the SSE connection opens', async () => {
    vi.stubGlobal('fetch', mockFetch(new Response(JSON.stringify([alert]), { status: 200 })))
    render(<App />)
    MockEventSource.instances[0].onopen?.()

    expect(await screen.findByText('Velocidad excesiva')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith('/api/alerts', undefined)
  })

  it('does not overwrite an alert received while loading the active snapshot', async () => {
    let completeRequest: ((response: Response) => void) | undefined
    const fetchMock = vi.fn(
      () => new Promise<Response>((resolve) => { completeRequest = resolve }),
    )
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)
    const source = MockEventSource.instances[0]
    source.onopen?.()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))

    source.emit('alert', alert)
    completeRequest?.(new Response(JSON.stringify([]), { status: 200 }))

    expect(await screen.findByText('Velocidad excesiva')).toBeInTheDocument()
  })

  it('looks up an explicitly entered vehicle ID through the state endpoint', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch(new Response(JSON.stringify(event), { status: 200 })),
    )
    render(<App />)
    fireEvent.change(screen.getByLabelText('Consultar vehículo por ID'), {
      target: { value: 'truck-17' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Consultar' }))

    await waitFor(() => expect(screen.getByText('4.711000')).toBeInTheDocument())
    expect(fetch).toHaveBeenCalledWith('/api/vehicles/truck-17/state', undefined)
  })

  it('closes the SSE connection when the dashboard unmounts', () => {
    const { unmount } = render(<App />)
    const source = MockEventSource.instances[0]
    unmount()
    expect(source.closed).toBe(true)
  })

  it('submits a question and displays the actual agent response and tools', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch(new Response(JSON.stringify({
        answer: 'truck-17 está en la posición registrada.',
        toolsUsed: ['get_vehicle_current_state'],
      }), { status: 200 })),
    )
    render(<App />)
    fireEvent.change(screen.getByLabelText('Escribe una pregunta'), {
      target: { value: '¿Dónde está truck-17?' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Enviar pregunta' }))

    expect(await screen.findByText('truck-17 está en la posición registrada.')).toBeInTheDocument()
    expect(screen.getByText(/get_vehicle_current_state/)).toBeInTheDocument()
  })

  it('shows a useful 503 message and allows retrying without reporting success', async () => {
    const fetchMock = mockFetch(new Response(JSON.stringify({
      code: 'AI_PROVIDER_UNAVAILABLE',
      detail: 'No fue posible completar la consulta con el asistente de IA.',
      status: 503,
    }), { status: 503 }))
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)
    fireEvent.change(screen.getByLabelText('Escribe una pregunta'), {
      target: { value: 'Consulta histórica' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Enviar pregunta' }))

    expect(await screen.findByText(/El asistente no está disponible/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar consulta' })).toBeInTheDocument()
    expect(screen.queryByText('Consulta histórica', { selector: '.chat-message.assistant p' })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('prevents duplicate sends while an AI request is pending', async () => {
    let completeRequest: ((response: Response) => void) | undefined
    const fetchMock = vi.fn(
      () => new Promise<Response>((resolve) => { completeRequest = resolve }),
    )
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)
    fireEvent.change(screen.getByLabelText('Escribe una pregunta'), {
      target: { value: 'Pregunta única' },
    })
    const sendButton = screen.getByRole('button', { name: 'Enviar pregunta' })
    fireEvent.click(sendButton)
    fireEvent.click(sendButton)

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('status')).toHaveTextContent('Consultando telemetría')
    completeRequest?.(new Response(JSON.stringify({ answer: 'Respuesta', toolsUsed: [] }), { status: 200 }))
    await screen.findByText('Respuesta')
  })
})
