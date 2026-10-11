import { useCallback, useMemo, useState, type FormEvent } from 'react'
import { ApiError, getVehicleState } from './api/client'
import {
  hasValidCoordinates,
  type BackendConnection,
  type TelemetryEvent,
} from './api/types'
import AgentChat from './components/AgentChat'
import FleetMap from './components/FleetMap'
import { useTelemetryStream, type StreamStatus } from './hooks/useTelemetryStream'
import './App.css'

function isNewer(candidate: TelemetryEvent, current: TelemetryEvent | undefined): boolean {
  return !current || Date.parse(candidate.timestamp) > Date.parse(current.timestamp)
}

function formatTimestamp(timestamp: string): string {
  const date = new Date(timestamp)
  return Number.isNaN(date.getTime())
    ? 'Timestamp no disponible'
    : new Intl.DateTimeFormat('es-CO', {
        dateStyle: 'medium',
        timeStyle: 'medium',
        timeZone: 'UTC',
      }).format(date) + ' UTC'
}

function App() {
  const [vehiclesById, setVehiclesById] = useState<Record<string, TelemetryEvent>>({})
  const [selectedVehicleId, setSelectedVehicleId] = useState<string | null>(null)
  const [vehicleQuery, setVehicleQuery] = useState('')
  const [lookupLoading, setLookupLoading] = useState(false)
  const [lookupError, setLookupError] = useState<string | null>(null)
  const [backendConnection, setBackendConnection] = useState<BackendConnection>('unknown')

  const handleTelemetry = useCallback((event: TelemetryEvent) => {
    setVehiclesById((current) => {
      const previous = current[event.vehicleId]
      if (!isNewer(event, previous)) return current
      return { ...current, [event.vehicleId]: event }
    })
    setBackendConnection('connected')
    setSelectedVehicleId((current) => current ?? event.vehicleId)
  }, [])

  const handleStreamStatus = useCallback((status: StreamStatus) => {
    setBackendConnection(status === 'connected' ? 'connected' : 'disconnected')
  }, [])
  const streamStatus = useTelemetryStream(handleTelemetry, handleStreamStatus)
  const vehicles = useMemo(
    () => Object.values(vehiclesById).sort((left, right) => left.vehicleId.localeCompare(right.vehicleId)),
    [vehiclesById],
  )
  const selectedVehicle = selectedVehicleId ? vehiclesById[selectedVehicleId] : undefined
  const positionedCount = vehicles.filter(hasValidCoordinates).length

  async function findVehicle(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const vehicleId = vehicleQuery.trim()
    if (!vehicleId || lookupLoading) return

    setLookupLoading(true)
    setLookupError(null)
    try {
      const state = await getVehicleState(vehicleId)
      setVehiclesById((current) =>
        isNewer(state, current[vehicleId]) ? { ...current, [vehicleId]: state } : current,
      )
      setSelectedVehicleId(vehicleId)
      setBackendConnection('connected')
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        setLookupError(`No hay telemetría persistida para ${vehicleId}.`)
      } else if (error instanceof ApiError && error.status === 0) {
        setLookupError('No se pudo conectar con el backend.')
        setBackendConnection('disconnected')
      } else {
        setLookupError('No fue posible consultar el estado del vehículo.')
      }
    } finally {
      setLookupLoading(false)
    }
  }

  function selectVehicle(vehicleId: string) {
    setSelectedVehicleId(vehicleId)
    setLookupError(null)
    void getVehicleState(vehicleId)
      .then((state) => {
        setVehiclesById((current) =>
          isNewer(state, current[vehicleId]) ? { ...current, [vehicleId]: state } : current,
        )
        setBackendConnection('connected')
      })
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 0) {
          setBackendConnection('disconnected')
        }
      })
  }

  const connectionLabel =
    backendConnection === 'connected'
      ? 'Backend conectado'
      : backendConnection === 'disconnected'
        ? 'Backend sin conexión'
        : 'Backend sin verificar'
  const streamLabel =
    streamStatus === 'connected'
      ? 'Tiempo real activo'
      : streamStatus === 'reconnecting'
        ? 'Reconectando SSE'
        : 'Conectando SSE'

  return (
    <main className="app-shell">
      <header className="topbar">
        <a className="brand" href="/" aria-label="Fleet Telemetry, inicio">
          <span className="brand-mark" aria-hidden="true"><i /><i /><i /></span>
          <span>fleet<span className="brand-light">telemetry</span></span>
        </a>
        <nav className="primary-nav" aria-label="Navegación principal">
          <a className="nav-link active" href="#dashboard" aria-current="page">
            <span className="nav-link-icon" aria-hidden="true">⌂</span>
            Dashboard
          </a>
        </nav>
      </header>

      <div className="topbar-right">
        <div className={`connection-indicator ${backendConnection}`} aria-label={connectionLabel}>
          <span className="status-dot" />
          <span>{connectionLabel}</span>
        </div>
        <div className={`connection-indicator stream-${streamStatus}`} aria-label={streamLabel}>
          <span className="status-dot" />
          <span>{streamLabel}</span>
        </div>
        <span className={`live-clock stream-${streamStatus}`}>
          <span className="clock-dot" />
          {streamStatus === 'connected'
            ? 'LIVE'
            : streamStatus === 'reconnecting'
              ? 'RETRY'
              : 'WAIT'}
        </span>
      </div>

      <section className="page-heading" id="dashboard">
        <div>
          <p className="eyebrow">CENTRO DE CONTROL / TELEMETRÍA</p>
          <h1>Visibilidad de flota<span>.</span></h1>
          <p className="page-description">
            Posiciones y actividad recibidas en tiempo real desde tu flota.
          </p>
        </div>
        <form className="vehicle-search" onSubmit={(event) => void findVehicle(event)}>
          <label className="sr-only" htmlFor="vehicle-id">Consultar vehículo por ID</label>
          <span className="search-icon" aria-hidden="true">⌕</span>
          <input
            id="vehicle-id"
            value={vehicleQuery}
            maxLength={100}
            placeholder="Consultar ID de vehículo"
            onChange={(event) => setVehicleQuery(event.target.value)}
          />
          <button type="submit" disabled={lookupLoading || !vehicleQuery.trim()}>
            {lookupLoading ? 'Buscando…' : 'Consultar'}
          </button>
        </form>
      </section>

      <section className="metrics-row" aria-label="Resumen de flota">
        <article className="metric-card">
          <div className="metric-icon vehicles-icon" aria-hidden="true">▤</div>
          <div><span className="metric-label">VEHÍCULOS CONOCIDOS</span><strong data-testid="vehicle-total">{vehicles.length}</strong></div>
          <span className="metric-note">vistos en esta sesión</span>
        </article>
        <article className="metric-card">
          <div className="metric-icon position-icon" aria-hidden="true">⌖</div>
          <div><span className="metric-label">POSICIONES VÁLIDAS</span><strong data-testid="position-total">{positionedCount}</strong></div>
          <span className="metric-note">coordenadas disponibles</span>
        </article>
        <article className="metric-card alert-metric">
          <div className="metric-icon alert-icon" aria-hidden="true">!</div>
          <div><span className="metric-label">ALERTAS</span><strong>—</strong></div>
          <span className="metric-note">no disponible en API</span>
        </article>
      </section>

      <div className="main-grid">
        <section className="panel map-panel" aria-labelledby="map-heading">
          <div className="panel-heading map-heading">
            <div>
              <span className="eyebrow">SEGUIMIENTO EN VIVO</span>
              <h2 id="map-heading">Mapa de posiciones</h2>
            </div>
            <div className="map-count"><span className="status-dot" />{positionedCount} posiciones</div>
          </div>
          {lookupError && <p className="inline-error" role="alert">{lookupError}</p>}
          <FleetMap
            vehicles={vehicles}
            selectedVehicleId={selectedVehicleId}
            onSelectVehicle={selectVehicle}
          />
          <div className="map-footer">
            <span>Las posiciones se actualizan al recibir eventos persistidos.</span>
            <span className="map-legend"><i /> Vehículo conocido</span>
          </div>
        </section>

        <aside className="side-column">
          <section className="panel vehicle-panel" aria-labelledby="vehicle-heading">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">DETALLE</span>
                <h2 id="vehicle-heading">Vehículo seleccionado</h2>
              </div>
              <span className="vehicle-symbol" aria-hidden="true">▤</span>
            </div>
            {selectedVehicle ? (
              <div className="vehicle-details">
                <div className="vehicle-name"><span className="vehicle-status-dot" />{selectedVehicle.vehicleId}</div>
                <div className="detail-row">
                  <span>Latitud</span>
                  <strong>{hasValidCoordinates(selectedVehicle) ? selectedVehicle.latitude.toFixed(6) : 'No disponible'}</strong>
                </div>
                <div className="detail-row">
                  <span>Longitud</span>
                  <strong>{hasValidCoordinates(selectedVehicle) ? selectedVehicle.longitude.toFixed(6) : 'No disponible'}</strong>
                </div>
                <div className="detail-row timestamp-row">
                  <span>Último evento</span>
                  <strong>{formatTimestamp(selectedVehicle.timestamp)}</strong>
                </div>
                <button className="refresh-button" type="button" onClick={() => selectVehicle(selectedVehicle.vehicleId)}>
                  <span aria-hidden="true">↻</span> Actualizar estado
                </button>
              </div>
            ) : (
              <div className="detail-empty">
                <span aria-hidden="true">⌖</span>
                <p>Selecciona un vehículo en el mapa o consulta su ID.</p>
              </div>
            )}
            <div className="discovery-note">
              <span aria-hidden="true">i</span>
              <p>El backend no ofrece un listado inicial. Los vehículos se descubren al llegar telemetría o al consultar un ID.</p>
            </div>
          </section>

          <section className="panel alerts-panel" aria-labelledby="alerts-heading">
            <div className="panel-heading">
              <div>
                <span className="eyebrow">CENTRO DE ALERTAS</span>
                <h2 id="alerts-heading">Alertas operativas</h2>
              </div>
              <span className="muted-count">—</span>
            </div>
            <div className="alerts-unavailable">
              <span aria-hidden="true">◇</span>
              <p>El backend aún no expone alertas ni reglas operativas.</p>
            </div>
          </section>
        </aside>
      </div>

      <AgentChat />
      <footer className="app-footer">
        <span><i /> TELEMETRÍA DE LA SESIÓN ACTUAL</span>
        <span>Fleet Telemetry <b>•</b> Datos servidos por el backend</span>
      </footer>
    </main>
  )
}

export default App
