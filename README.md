# Fleet Telemetry

## Ingesta inicial de telemetría

La primera etapa implementa `POST /api/telemetry`: valida el cuerpo HTTP, construye un evento de dominio inmutable y lo publica como JSON en el tópico Kafka `telemetry.events`. La clave Kafka es el UUID del evento, lo que conserva el orden de las observaciones del mismo evento en una partición.

El publicador espera el acuse del broker antes de responder `202 Accepted`. Si Kafka no confirma la publicación, la API devuelve `503 Service Unavailable`; el cliente puede reintentar el mismo ID. El productor usa `acks=all`, idempotencia de productor y un tiempo máximo de entrega configurado. La validación del cuerpo devuelve `400 Bad Request`. Un reintento HTTP con el mismo ID vuelve a publicar el evento: la idempotencia Kafka cubre reintentos internos del productor, no deduplica peticiones de negocio.

El adaptador web no depende de Kafka directamente: el caso de uso depende del puerto de publicación y el adaptador Kafka implementa ese puerto. Kafka recibe un listener interno (`kafka:9092`) y uno externo (`localhost:29092`) para permitir tanto la comunicación de Compose como la ejecución local de Spring Boot.

Los eventos recibidos por el consumidor Kafka se guardan en `telemetry_events` en PostgreSQL/TimescaleDB. `event_id` es clave primaria y la inserción usa `ON CONFLICT DO NOTHING`; una entrega repetida no duplica el evento, pero fallos reales de base de datos se propagan al contenedor Kafka. Si un productor reutiliza un `eventId` con contenido distinto, hoy también se ignora silenciosamente el segundo contenido y prevalece el primer registro. Como mejora pendiente, el adaptador de persistencia debería distinguir duplicados idénticos de colisiones de ID comparando el contenido existente; una colisión debería registrarse y tratarse como error no reintentable o enviarse a una dead-letter topic, sin sobrescribir la telemetría original. La tabla usa `TIMESTAMPTZ` para conservar el instante UTC. Es una tabla PostgreSQL normal (no hypertable), porque la restricción global por `event_id` no sería válida en una hypertable particionada por tiempo.

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

### Flujo de telemetría en tiempo real (SSE)

Los clientes pueden abrir `GET /api/telemetry/stream` y mantener la conexión para recibir eventos `telemetry` de nuevas telemetrías después de persistirse correctamente. Cada evento lleva como `id` el `eventId` y como datos el JSON con `eventId`, `vehicleId`, `latitude`, `longitude` y `timestamp`. Los heartbeats SSE se envían cada 15 segundos por defecto (`app.sse.heartbeat-interval-ms`).

Prueba manual desde PowerShell:

```powershell
curl.exe -N -H "Accept: text/event-stream" http://localhost:8080/api/telemetry/stream
```

Con esa conexión abierta, envía un nuevo `POST /api/telemetry` con un `eventId` único. La notificación solo se difunde después de que la transacción de inserción termina; las entregas duplicadas no producen un evento SSE nuevo. SSE es una difusión efímera a conexiones activas, no un registro duradero ni una garantía de entrega/reproducción: los clientes que están desconectados no reciben los eventos pasados. Kafka y PostgreSQL siguen siendo los mecanismos de procesamiento y persistencia.

### Consultar telemetría mediante el agente de IA

`POST /api/ai/chat` recibe una pregunta en español de hasta 2000 caracteres. El agente utiliza únicamente herramientas de solo lectura respaldadas por los casos de uso de la aplicación; no realiza llamadas HTTP internas ni permite consultas SQL generadas por el modelo. La respuesta incluye `answer` y `toolsUsed`. Las herramientas disponibles son:

- `get_vehicle_current_state`: estado más reciente de un vehículo.
- `count_vehicle_reports`: cantidad de eventos persistidos para un vehículo y un periodo.
- `get_vehicle_telemetry_history`: página de eventos de telemetría de un periodo o los eventos más recientes.
- `get_vehicle_period_boundaries`: primera y última telemetría de un vehículo en un periodo.

Las fechas se resuelven en el backend, nunca por el modelo: `today` y `yesterday` son días calendario de `America/Bogota`; `last_hour`, `last_24_hours` y `last_7_days` son periodos móviles hasta el instante actual UTC. Los intervalos consultados incluyen el inicio y excluyen el fin. Para fechas explícitas se acepta ISO-8601 con zona/offset, hora local (interpretada en Bogotá) o fecha `YYYY-MM-DD`; una fecha final sin hora incluye ese día completo. Las consultas históricas usan solo registros persistidos en `telemetry_events`, por lo que los mensajes repetidos de Kafka no incrementan el conteo. Los eventos con igual timestamp se ordenan por `event_id` para una paginación determinista; las páginas se entregan en orden cronológico ascendente y cada timestamp se pasa al modelo como texto ISO-8601 UTC. `period=all` solo permite solicitar una lista paginada de eventos recientes, no un conteo de todo el histórico.

El tamaño máximo de página se configura con `AI_HISTORY_MAX_RESULTS` (predeterminado `100`, rango `1`–`1000`); el offset está acotado a `100000`. El índice `(vehicle_id, event_timestamp, event_id)` soporta estas consultas. Una pregunta sin periodo para conteos, historial o extremos debe solicitar una aclaración; no se interpreta como consulta de todo el histórico.

La integración usa `RestClient` y el endpoint Groq compatible con Chat Completions, sin añadir otra dependencia. La configuración canónica es `GROQ_API_KEY`, `GROQ_MODEL` y `GROQ_BASE_URL`. Los valores predeterminados son `https://api.groq.com/openai/v1` y `openai/gpt-oss-20b`. Para realizar consultas, configura en la sesión de PowerShell:

```powershell
$env:GROQ_API_KEY = "TU_CLAVE_DE_GROQ"
$env:GROQ_MODEL = "openai/gpt-oss-20b"
$env:GROQ_BASE_URL = "https://api.groq.com/openai/v1"
```

Por compatibilidad, `OPENAI_API_KEY`, `OPENAI_MODEL` y `OPENAI_BASE_URL` se mantienen como aliases heredados: cada variable `GROQ_*` tiene prioridad sobre su alias `OPENAI_*`. Si no se define URL/modelo, se usan los valores predeterminados Groq anteriores. `GROQ_API_KEY` (o el alias heredado) se lee solo desde el entorno, no se registra y no es necesaria para arrancar la aplicación; el log de inicio informa únicamente el host, modelo y si hay una clave configurada. Docker Compose no ejecuta el backend, así que al arrancarlo desde el host debes definir estas variables en la misma sesión de PowerShell. Las pruebas usan claves ficticias/mocks y no requieren credenciales reales. La conexión tiene timeout de 3 segundos y la respuesta de 20 segundos. El agente realiza como máximo dos llamadas al proveedor por pregunta y ejecuta como máximo una invocación de herramienta por turno.

Los esquemas de las herramientas no fuerzan `strict` y permiten omitir los límites `from` y `to` cuando no se consulta un periodo explícito; el backend trata su ausencia como nulo y valida el periodo antes de consultar. Esto evita que Groq rechace llamadas de historial reciente con `tool_use_failed` por omitir esos dos argumentos opcionales. Las respuestas de herramientas en el transcript incluyen `tool_call_id` y `name`, además del rol y el resultado.

Para diagnosticar una conversación sin registrar prompts ni datos del vehículo, cada solicitud devuelve `X-Correlation-ID`. Busca ese valor en los logs `AI provider request started/completed`, `AI tool selected/completed/failed` y en el manejador HTTP. Se registran proveedor, modelo, etapa (`initial request` o `after tool execution`), status HTTP, duración, nombre de herramienta y resultado resumido. Los errores HTTP del proveedor incluyen su código/mensaje sanitizados; no se registran claves ni encabezados de autorización. El inicio de llamada se registra a nivel INFO y las excepciones de validación/consulta con WARN o ERROR según corresponda.

Ejemplo para Postman: método `POST`, URL `http://localhost:8080/api/ai/chat`, `Content-Type: application/json`:

```json
{
  "question": "¿Cuál es la última posición conocida del vehículo truck-17?"
}
```

La misma forma de solicitud sirve para preguntas históricas, por ejemplo:

```json
{
  "question": "¿Cuántas telemetrías persistidas recibió truck-17 ayer?"
}
```

```json
{
  "question": "Muestra las últimas 5 telemetrías de truck-17."
}
```

```json
{
  "question": "¿Cuál fue la primera y la última posición de truck-17 entre 2026-10-01 y 2026-10-07?"
}
```

Una pregunta sobre un vehículo sin registros devuelve una respuesta explícita indicando que no hay telemetría. Los fallos del proveedor y de la consulta se distinguen con errores Problem Details `AI_PROVIDER_UNAVAILABLE` y `AI_TELEMETRY_QUERY_UNAVAILABLE`. Las pruebas usan dobles y un servidor HTTP simulado; no requieren una API key real.

### Comprobar la persistencia

Envía una petición válida a `POST /api/telemetry`, después consulta PostgreSQL:

```powershell
docker compose exec db psql -U fleet -d fleet -c "SELECT event_id, vehicle_id, latitude, longitude, event_timestamp FROM telemetry_events ORDER BY event_timestamp DESC LIMIT 10;"
```

Para ejecutar las pruebas del backend, incluida la integración de consumidor con Kafka embebido y PostgreSQL de Testcontainers:

```powershell
.\backend\mvnw.cmd -f backend\pom.xml test
```