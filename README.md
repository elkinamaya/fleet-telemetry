# Fleet Telemetry

## Ingesta inicial de telemetría

La primera etapa implementa `POST /api/telemetry`: valida el cuerpo HTTP, construye un evento de dominio inmutable y lo publica como JSON en el tópico Kafka `telemetry.events`. La clave Kafka es el UUID del evento, lo que conserva el orden de las observaciones del mismo evento en una partición.

El publicador espera el acuse del broker antes de responder `202 Accepted`. Si Kafka no confirma la publicación, la API devuelve `503 Service Unavailable`; el cliente puede reintentar el mismo ID. El productor usa `acks=all`, idempotencia de productor y un tiempo máximo de entrega configurado. La validación del cuerpo devuelve `400 Bad Request`. Un reintento HTTP con el mismo ID vuelve a publicar el evento: la idempotencia Kafka cubre reintentos internos del productor, no deduplica peticiones de negocio.

El adaptador web no depende de Kafka directamente: el caso de uso depende del puerto de publicación y el adaptador Kafka implementa ese puerto. Kafka recibe un listener interno (`kafka:9092`) y uno externo (`localhost:29092`) para permitir tanto la comunicación de Compose como la ejecución local de Spring Boot.

Los eventos recibidos por el consumidor Kafka se guardan en `telemetry_events` en PostgreSQL/TimescaleDB. `event_id` es clave primaria y la inserción usa `ON CONFLICT DO NOTHING`; una entrega repetida no duplica el evento, pero fallos reales de base de datos se propagan al contenedor Kafka. La tabla usa `TIMESTAMPTZ` para conservar el instante UTC. Es una tabla PostgreSQL normal (no hypertable), porque la restricción global por `event_id` no sería válida en una hypertable particionada por tiempo.

### Ejecutar localmente

```powershell
docker compose up -d kafka db
cd backend
.\mvnw.cmd spring-boot:run
```

La aplicación ejecutada en el host se conecta a Kafka por `localhost:29092` y PostgreSQL por `localhost:5432`. Los valores por defecto de base de datos coinciden con Compose (`fleet`/`fleet`/`local_dev_password`); se pueden sobrescribir con `SPRING_DATASOURCE_URL`, `POSTGRES_USER` y `POSTGRES_PASSWORD`. Para usar una contraseña personalizada definida en `.env`, exporta también `$env:POSTGRES_PASSWORD` en la sesión de PowerShell desde la que arranques Spring.

Ejemplo de solicitud:

```json
{
  "eventId": "a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50",
  "vehicleId": "truck-17",
  "latitude": 40.7128,
  "longitude": -74.006,
  "timestamp": "2026-10-09T15:00:00Z"
}
```

### Fallos conocidos y alcance pendiente

- Un error de publicación devuelve `503`; no hay almacenamiento local de respaldo. El cliente decide cuándo reintentar y debe reutilizar el mismo `eventId`.
- El consumidor reintenta con espera de un segundo dos veces. Si persiste el fallo, el registro no se recupera como exitoso y Kafka lo vuelve a entregar; el registro fallido puede detener el avance de su partición hasta que se resuelva la causa.
- La respuesta HTTP `202` confirma la publicación en Kafka, no que el consumidor ya haya persistido el evento.

### Consultar el estado actual de un vehículo

`GET /api/vehicles/{vehicleId}/state` devuelve `200 OK` con el ID del evento, vehículo, latitud, longitud y timestamp del evento de telemetría más reciente según `event_timestamp` (no el orden de inserción). Si no hay eventos para el vehículo, devuelve `404 Not Found`.

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/vehicles/truck-17/state"
```

Los errores HTTP usan `application/problem+json` con la estructura Problem Details (`type`, `title`, `status`, `detail`, `instance`) y un código estable `code`. Las solicitudes mal formadas o inválidas responden `400`; un vehículo sin telemetría usa `VEHICLE_TELEMETRY_NOT_FOUND`; si Kafka no confirma el envío durante el POST, la API responde `503`; los errores inesperados responden `500` con un detalle genérico. Estos errores del POST no reflejan el resultado posterior del consumidor: un `202` solo confirma que Kafka recibió el evento.

### Comprobar la persistencia

Envía una petición válida a `POST /api/telemetry`, después consulta PostgreSQL:

```powershell
docker compose exec db psql -U fleet -d fleet -c "SELECT event_id, vehicle_id, latitude, longitude, event_timestamp FROM telemetry_events ORDER BY event_timestamp DESC LIMIT 10;"
```

Para ejecutar las pruebas del backend, incluida la integración de consumidor con Kafka embebido y PostgreSQL de Testcontainers:

```powershell
.\backend\mvnw.cmd -f backend\pom.xml test
```