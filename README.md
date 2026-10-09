# Fleet Telemetry

## Ingesta inicial de telemetría

La primera etapa implementa `POST /api/telemetry`: valida el cuerpo HTTP, construye un evento de dominio inmutable y lo publica como JSON en el tópico Kafka `telemetry.events`. La clave Kafka es el UUID del evento, lo que conserva el orden de las observaciones del mismo evento en una partición.

El publicador espera el acuse del broker antes de responder `202 Accepted`. Si Kafka no confirma la publicación, la API devuelve `503 Service Unavailable`; el cliente puede reintentar el mismo ID. El productor usa `acks=all`, idempotencia de productor y un tiempo máximo de entrega configurado. La validación del cuerpo devuelve `400 Bad Request`. Un reintento HTTP con el mismo ID vuelve a publicar el evento: la idempotencia Kafka cubre reintentos internos del productor, no deduplica peticiones de negocio.

El adaptador web no depende de Kafka directamente: el caso de uso depende del puerto de publicación y el adaptador Kafka implementa ese puerto. Kafka recibe un listener interno (`kafka:9092`) y uno externo (`localhost:29092`) para permitir tanto la comunicación de Compose como la ejecución local de Spring Boot.

Como esta etapa aún no accede a PostgreSQL, la autoconfiguración de JPA y del origen de datos está deshabilitada temporalmente; el servicio `db` de Compose queda preparado para la etapa de persistencia.

### Ejecutar localmente

```powershell
docker compose up -d kafka
cd backend
.\mvnw.cmd spring-boot:run
```

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

- Esta etapa aún no incluye consumidor ni persistencia en PostgreSQL/TimescaleDB. Por ello, el ID estable y la idempotencia del productor Kafka no impiden que eventos duplicados se almacenen más adelante; la deduplicación debe implementarse en la persistencia del consumidor.
- Un error de publicación devuelve `503`; no hay almacenamiento local de respaldo. El cliente decide cuándo reintentar y debe reutilizar el mismo `eventId`.
- La respuesta HTTP espera la confirmación Kafka y puede tardar hasta el límite de entrega del productor.