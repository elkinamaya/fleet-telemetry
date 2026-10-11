import { useEffect } from 'react'
import { CircleMarker, MapContainer, Popup, TileLayer, useMap } from 'react-leaflet'
import { hasValidCoordinates, type TelemetryEvent } from '../api/types'

interface FleetMapProps {
  vehicles: TelemetryEvent[]
  selectedVehicleId: string | null
  onSelectVehicle: (vehicleId: string) => void
}

function MapViewport({ vehicles, selectedVehicleId }: Pick<FleetMapProps, 'vehicles' | 'selectedVehicleId'>) {
  const map = useMap()

  useEffect(() => {
    const selected = vehicles.find((vehicle) => vehicle.vehicleId === selectedVehicleId)
    if (selected && hasValidCoordinates(selected)) {
      map.setView([selected.latitude, selected.longitude], Math.max(map.getZoom(), 8))
      return
    }
    if (vehicles.length === 1) {
      map.setView([vehicles[0].latitude, vehicles[0].longitude], 8)
      return
    }
    if (vehicles.length > 1) {
      map.fitBounds(vehicles.map(({ latitude, longitude }) => [latitude, longitude]), {
        padding: [36, 36],
        maxZoom: 12,
      })
    }
  }, [map, selectedVehicleId, vehicles])

  return null
}

export default function FleetMap({
  vehicles,
  selectedVehicleId,
  onSelectVehicle,
}: FleetMapProps) {
  const validVehicles = vehicles.filter(hasValidCoordinates)
  if (validVehicles.length === 0) {
    return (
      <div className="map-empty" role="status">
        <span className="map-empty-icon" aria-hidden="true">⌖</span>
        <strong>No hay posiciones para mostrar</strong>
        <span>Las posiciones aparecerán al recibir telemetría válida.</span>
      </div>
    )
  }

  return (
    <MapContainer className="fleet-map" center={[0, 0]} zoom={2} scrollWheelZoom>
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <MapViewport vehicles={validVehicles} selectedVehicleId={selectedVehicleId} />
      {validVehicles.map((vehicle) => (
        <CircleMarker
          key={vehicle.vehicleId}
          center={[vehicle.latitude, vehicle.longitude]}
          radius={vehicle.vehicleId === selectedVehicleId ? 10 : 7}
          pathOptions={{
            color: vehicle.vehicleId === selectedVehicleId
              ? 'var(--color-teal-bright)'
              : 'var(--color-teal)',
            fillColor: vehicle.vehicleId === selectedVehicleId
              ? 'var(--color-teal-bright)'
              : 'var(--color-teal)',
            fillOpacity: 0.9,
            weight: 2,
          }}
          eventHandlers={{ click: () => onSelectVehicle(vehicle.vehicleId) }}
        >
          <Popup>
            <strong>{vehicle.vehicleId}</strong>
            <br />
            {vehicle.latitude.toFixed(5)}, {vehicle.longitude.toFixed(5)}
          </Popup>
        </CircleMarker>
      ))}
    </MapContainer>
  )
}
