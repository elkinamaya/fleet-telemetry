# Fleet Telemetry

## Ingesta inicial de telemetría

`POST /api/telemetry` valida el cuerpo HTTP, construye un evento de dominio inmutable y lo publica como JSON en el tópico Kafka `telemetry.events`. El evento incluye `speed` en km/h cuando el productor la conoce. La clave Kafka es `vehicleId`, para mantener las observaciones de cada vehículo en una misma partición y en su orden de publicación; `eventId` conserva la idempotencia de persistencia.

El publicador espera el acuse del broker antes de responder `202 Accepted`. Si Kafka no confirma la publicación, la API devuelve `503 Service Unavailable`; el cliente puede reintentar el mismo ID. El productor usa `acks=all`, idempotencia de productor y un tiempo máximo de entrega configurado. La validación del cuerpo devuelve `400 Bad Request`. Latitud y longitud son obligatorias; `speed` es opcional para mantener compatibilidad con productores anteriores. Cuando se envía, debe ser finita y no negativa; un valor ausente se conserva como desconocido (`null`) y no se usa para evaluar reglas de velocidad. Un reintento HTTP con el mismo ID vuelve a publicar el evento: la idempotencia Kafka cubre reintentos internos del productor, no deduplica peticiones de negocio.

El adaptador web no depende de Kafka directamente: el caso de uso depende del puerto de publicación y el adaptador Kafka implementa ese puerto. Kafka recibe un listener interno (`kafka:9092`) y uno externo (`localhost:29092`) para permitir tanto la comunicación de Compose como la ejecución local de Spring Boot.

Los eventos recibidos por el consumidor Kafka se guardan en `telemetry_events` en PostgreSQL/TimescaleDB. `event_id` es clave primaria y la inserción usa `ON CONFLICT DO NOTHING`; una entrega repetida no duplica el evento, pero fallos reales de base de datos se propagan al contenedor Kafka. Si un productor reutiliza un `eventId` con contenido distinto, hoy también se ignora silenciosamente el segundo contenido y prevalece el primer registro. Como mejora pendiente, el adaptador de persistencia debería distinguir duplicados idénticos de colisiones de ID comparando el contenido existente; una colisión debería registrarse y tratarse como error no reintentable o enviarse a una dead-letter topic, sin sobrescribir la telemetría original. La tabla usa `TIMESTAMPTZ` para conservar el instante UTC. Es una tabla PostgreSQL normal (no hypertable), porque la restricción global por `event_id` no sería válida en una hypertable particionada por tiempo.

La columna `speed` es nullable para conservar registros históricos. `schema.sql` agrega la columna con `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, por lo que una base existente se actualiza sin recrear la tabla ni perder eventos. La base también rechaza velocidades negativas o no finitas.

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
  "timestamp": "2026-10-09T15:00:00Z",
  "speed": 54.5
}
```

`speed` está expresada en km/h. Los clientes antiguos pueden omitirla; los estados e historiales devuelven `null` cuando no existe una velocidad disponible.

### Fallos conocidos y alcance pendiente

- Un error de publicación devuelve `503`; no hay almacenamiento local de respaldo. El cliente decide cuándo reintentar y debe reutilizar el mismo `eventId`.
- El consumidor reintenta con espera de un segundo dos veces. Si persiste el fallo, el registro no se recupera como exitoso y Kafka lo vuelve a entregar; el registro fallido puede detener el avance de su partición hasta que se resuelva la causa.
- La respuesta HTTP `202` confirma la publicación en Kafka, no que el consumidor ya haya persistido el evento.

### Prueba inicial de carga con k6

El script `load/k6/telemetry.js` prueba únicamente `POST /api/telemetry`; no llama al proveedor de IA ni necesita claves externas. Requiere k6 instalado aparte (no es una dependencia Maven o npm), Kafka y PostgreSQL iniciados con Docker Compose y el backend ejecutándose en el host. Desde la raíz del repositorio, crea un `RUN_ID` nuevo para cada ejecución y ejecuta una prueba de humo de un usuario virtual:

```powershell
$runId = [guid]::NewGuid().ToString("N")
k6 run -e BASE_URL=http://localhost:8080 -e RUN_ID=$runId -e MAX_VUS=1 -e STAGE_SECONDS=10 -e THINK_TIME_SECONDS=1 -e P95_MAX_MS=1500 -e CHECK_FAILURE_RATE_MAX=0.01 load\k6\telemetry.js
```

El escenario empieza con 1 usuario virtual, sube progresivamente por escalones de 5, 10, 20, 50, 100, 200, 500 y 1000 (omitiendo los que superen `MAX_VUS`) y mantiene el máximo configurado durante una etapa final. `STAGE_SECONDS` configura la duración de cada escalón y de esa etapa final; no representa una tasa de eventos por segundo. Cada VU envía una solicitud y espera `THINK_TIME_SECONDS` antes de la siguiente. Empieza con `MAX_VUS=1` y aumenta el máximo gradualmente, usando un `RUN_ID` nuevo por ejecución. El umbral exige al menos un POST aceptado para evitar que una ejecución sin iteraciones se considere válida.

`RUN_ID` es obligatorio y se incorpora al prefijo `vehicleId` (`k6-<RUN_ID>-<VU>`), de modo que cada ejecución tenga vehículos identificables y cada VU use un ID distinto. Cada solicitud genera un `eventId` UUID nuevo. Los parámetros admiten estos valores predeterminados: `BASE_URL=http://localhost:8080`, `MAX_VUS=1`, `STAGE_SECONDS=10`, `THINK_TIME_SECONDS=1`, `P95_MAX_MS=1500` y `CHECK_FAILURE_RATE_MAX=0.01`. k6 registra la duración HTTP (`http_req_duration`), la tasa de checks y los contadores `telemetry_posts_accepted` y `telemetry_http_errors`. La prueba falla si el p95 supera `P95_MAX_MS` o la tasa de checks fallidos supera `CHECK_FAILURE_RATE_MAX`; son umbrales iniciales configurables, no garantías de rendimiento.

Un `202 Accepted` confirma que Kafka recibió la publicación, no que el consumidor ya haya persistido la fila en PostgreSQL. Tras permitir que el consumidor drene los eventos, contrasta `telemetry_posts_accepted` del resumen de k6 con las filas de esa ejecución (conserva `$runId` en la misma sesión PowerShell):

```powershell
docker compose exec db psql -U fleet -d fleet -c "SELECT count(*) AS persisted_events FROM telemetry_events WHERE vehicle_id LIKE 'k6-$runId-%';"
```

La consulta no elimina ni modifica registros. Si los conteos difieren, revisa el tiempo de drenaje y los logs del consumidor; no interpretes el resultado HTTP por sí solo como confirmación de persistencia.

### Consultar el estado actual de un vehículo

`GET /api/vehicles/{vehicleId}/state` devuelve `200 OK` con el ID del evento, vehículo, latitud, longitud, velocidad en km/h (o `null` si no estaba disponible) y timestamp del evento de telemetría más reciente según `event_timestamp` (no el orden de inserción). Los resultados del historial que usa el agente de IA incluyen también `speed`. Si no hay eventos para el vehículo, devuelve `404 Not Found`.

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8080/api/vehicles/truck-17/state"
```

Los errores HTTP usan `application/problem+json` con la estructura Problem Details (`type`, `title`, `status`, `detail`, `instance`) y un código estable `code`. Las solicitudes mal formadas o inválidas responden `400`; un vehículo sin telemetría usa `VEHICLE_TELEMETRY_NOT_FOUND`; si Kafka no confirma el envío durante el POST, la API responde `503`; los errores inesperados responden `500` con un detalle genérico. Estos errores del POST no reflejan el resultado posterior del consumidor: un `202` solo confirma que Kafka recibió el evento.

### Flujo de telemetría en tiempo real (SSE)

Los clientes pueden abrir `GET /api/telemetry/stream` y mantener la conexión para recibir eventos `telemetry` de nuevas telemetrías después de persistirse correctamente. Cada evento lleva como `id` el `eventId` y como datos el JSON con `eventId`, `vehicleId`, `latitude`, `longitude`, `timestamp` y `speed`. Los heartbeats SSE se envían cada 15 segundos por defecto (`app.sse.heartbeat-interval-ms`).

Prueba manual desde PowerShell:

```powershell
curl.exe -N -H "Accept: text/event-stream" http://localhost:8080/api/telemetry/stream
```

Con esa conexión abierta, envía un nuevo `POST /api/telemetry` con un `eventId` único. La notificación solo se difunde después de que la transacción de inserción termina; las entregas duplicadas no producen un evento SSE nuevo. SSE es una difusión efímera a conexiones activas, no un registro duradero ni una garantía de entrega/reproducción: los clientes que están desconectados no reciben los eventos pasados. Kafka y PostgreSQL siguen siendo los mecanismos de procesamiento y persistencia.

### Alertas operativas

El centro de alertas usa el estado operativo en memoria del backend; no se guardan alertas ni se mantiene un historial. `GET /api/alerts` devuelve la instantánea de alertas activas conocida por el proceso. El mismo stream SSE publica eventos `alert` con `alertId`, `vehicleId`, `type`, `description`, `detectedAt`, `updatedAt`, `speed` y `status` (`ACTIVE` o `RESOLVED`). El frontend consulta la instantánea al conectar o reconectar y aplica las transiciones SSE en vivo. Una telemetría nueva se muestra además como aviso informativo temporal en pantalla; no se cuenta como alerta activa ni se persiste.

Reglas actuales:

- **Vehículo detenido:** dos muestras consecutivas con velocidad disponible ≤ 3 km/h separadas por más de 20 minutos. El cálculo usa los timestamps de las muestras, no un intervalo fijo. Esta regla confirma que las muestras observadas cumplen el criterio; con solo puntos discretos no prueba que la velocidad se mantuviera baja durante cada instante intermedio. Una velocidad > 3 km/h resuelve la alerta.
- **Velocidad excesiva:** dos muestras consecutivas > 80 km/h. Una muestra ≤ 80 km/h resuelve la alerta. Los umbrales y duración están centralizados en `VehicleAlertMonitor`.
- **Sin reportes recientes:** se evalúa cada 30 segundos y se activa cuando han transcurrido al menos 15 minutos desde el timestamp de la última telemetría válida. Una nueva telemetría cronológicamente posterior la resuelve; la regla indica pérdida de comunicación, no detención.

Las condiciones activas y candidatos se mantienen solo en memoria. Se pierden al reiniciar el backend y vuelven a formarse con telemetrías posteriores. Como no existe un listado histórico de vehículos, solo se monitorizan los vehículos conocidos por el proceso desde su inicio; no se presenta como monitorizado un vehículo histórico que no haya sido observado en esa ejecución. Eventos persistidos con timestamps anteriores al último evento operativo conocido se ignoran para cambiar las alertas, aunque se conservan y se difunden como telemetría nueva.

### Consultar telemetría mediante el agente de IA

`POST /api/ai/chat` recibe una pregunta en español de hasta 2000 caracteres. El agente utiliza únicamente herramientas de solo lectura respaldadas por los casos de uso de la aplicación; no realiza llamadas HTTP internas ni permite consultas SQL generadas por el modelo. La respuesta incluye `answer` y `toolsUsed`. Las herramientas disponibles son:

- `get_vehicle_current_state`: estado más reciente de un vehículo.
- `count_vehicle_reports`: cantidad de eventos persistidos para un vehículo y un periodo.
- `get_vehicle_telemetry_history`: página de eventos de telemetría de un periodo o los eventos más recientes.
- `get_vehicle_period_boundaries`: primera y última telemetría de un vehículo en un periodo.

Las fechas se resuelven en el backend, nunca por el modelo: `today` y `yesterday` son días calendario de `America/Bogota`; `last_hour`, `last_24_hours` y `last_7_days` son periodos móviles hasta el instante actual UTC. Los intervalos consultados incluyen el inicio y excluyen el fin. Para fechas explícitas se acepta ISO-8601 con zona/offset, hora local (interpretada en Bogotá) o fecha `YYYY-MM-DD`; una fecha final sin hora incluye ese día completo. Las consultas históricas usan solo registros persistidos en `telemetry_events`, por lo que los mensajes repetidos de Kafka no incrementan el conteo. Los eventos con igual timestamp se ordenan por `event_id` para una paginación determinista; las páginas se entregan en orden cronológico ascendente y cada timestamp se pasa al modelo como texto ISO-8601 UTC. `period=all` solo permite solicitar una lista paginada de eventos recientes, no un conteo de todo el histórico.

El tamaño máximo de página se configura con `AI_HISTORY_MAX_RESULTS` (predeterminado `100`, rango `1`–`1000`); el offset está acotado a `100000`. El índice `(vehicle_id, event_timestamp, event_id)` soporta estas consultas. Una pregunta sin periodo para conteos, historial o extremos debe solicitar una aclaración; no se interpreta como consulta de todo el histórico.

La integración usa `RestClient` y el endpoint Groq compatible con Chat Completions; `resilience4j-circuitbreaker` protege la llamada HTTP saliente. La configuración canónica es `GROQ_API_KEY`, `GROQ_MODEL` y `GROQ_BASE_URL`. Los valores predeterminados son `https://api.groq.com/openai/v1` y `openai/gpt-oss-20b`. Para realizar consultas, configura en la sesión de PowerShell:

```powershell
$env:GROQ_API_KEY = "TU_CLAVE_DE_GROQ"
$env:GROQ_MODEL = "openai/gpt-oss-20b"
$env:GROQ_BASE_URL = "https://api.groq.com/openai/v1"
```

Por compatibilidad, `OPENAI_API_KEY`, `OPENAI_MODEL` y `OPENAI_BASE_URL` se mantienen como aliases heredados: cada variable `GROQ_*` tiene prioridad sobre su alias `OPENAI_*`. Si no se define URL/modelo, se usan los valores predeterminados Groq anteriores. `GROQ_API_KEY` (o el alias heredado) se lee solo desde el entorno, no se registra y no es necesaria para arrancar la aplicación; el log de inicio informa únicamente el host, modelo y si hay una clave configurada. Docker Compose no ejecuta el backend, así que al arrancarlo desde el host debes definir estas variables en la misma sesión de PowerShell. Las pruebas usan claves ficticias/mocks y no requieren credenciales reales. La conexión tiene timeout de 3 segundos y la respuesta de 20 segundos. El agente realiza como máximo dos llamadas al proveedor por pregunta y ejecuta como máximo una invocación de herramienta por turno.

Las solicitudes HTTP salientes a Groq están protegidas por el Circuit Breaker `groq` de Resilience4j. Sus valores configurables son `AI_GROQ_CB_SLIDING_WINDOW_SIZE` (10), `AI_GROQ_CB_MINIMUM_NUMBER_OF_CALLS` (5), `AI_GROQ_CB_FAILURE_RATE_THRESHOLD` (50 %), `AI_GROQ_CB_WAIT_DURATION_IN_OPEN_STATE_SECONDS` (15), `AI_GROQ_CB_PERMITTED_CALLS_IN_HALF_OPEN_STATE` (2) y `AI_GROQ_CB_AUTOMATIC_TRANSITION` (`false`). Cuenta como fallo solamente un timeout/error de acceso, una respuesta HTTP 5xx o una respuesta remota malformada. Las respuestas 4xx, incluida la 429, y cualquier excepción que no sea de comunicación transitoria se ignoran en las métricas (no cuentan como éxito ni como fallo); ausencia de clave, validación, herramientas y consultas de PostgreSQL quedan fuera de la operación protegida. Las respuestas 4xx mantienen el error público actual `503 AI_PROVIDER_UNAVAILABLE`, igual que el rechazo por circuito abierto. No se realizan reintentos automáticos y los timeouts existentes no cambian.

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

## Dashboard web

El dashboard React/TypeScript consume el backend existente; no genera vehículos, coordenadas ni alertas de demostración.

### Requisitos e inicio

Se requiere Node.js `^20.19.0` o `>=22.12.0` (requisito de Vite 8) y npm. En una terminal, inicia la infraestructura y el backend siguiendo los pasos anteriores. En otra terminal PowerShell:

```powershell
cd frontend
npm ci
npm run dev
```

Abre la URL local que muestra Vite (por defecto `http://localhost:5173`). El proxy de desarrollo de Vite reenvía `/api` a `http://localhost:8080`, por lo que el backend local no requiere configuración CORS para este modo. `frontend/.env.example` documenta `VITE_API_BASE_URL`; su valor predeterminado `/api` sirve para el proxy y para despliegues detrás de un proxy same-origin. Si se configura una URL completa para otra API, debe incluir la ruta `/api` y ese backend debe permitir el origen del navegador mediante CORS. No configures secretos en variables `VITE_*`.

Comandos desde `frontend`:

```powershell
npm run dev
npm test
npm run lint
npm run build
```

### Integraciones y límites reales

- `GET /api/vehicles/{vehicleId}/state`: consulta el estado persistido más reciente del ID seleccionado/introducido; el `404` se presenta como ausencia de telemetría.
- `GET /api/telemetry/stream`: escucha eventos SSE llamados `telemetry` y `alert`. `telemetry` contiene `eventId`, `vehicleId`, `latitude`, `longitude`, `timestamp` y `speed` (nullable para compatibilidad); `alert` comunica activación/resolución operativa. El navegador reconecta con el comportamiento nativo de `EventSource` y consulta `GET /api/alerts` al reconectar; la conexión se cierra al desmontar el dashboard. Los IDs de telemetría duplicados en la sesión se descartan y una observación con timestamp anterior no reemplaza otra más reciente.
- `POST /api/ai/chat`: envía `{ "question": "..." }`, presenta `answer` y `toolsUsed`; `AI_PROVIDER_UNAVAILABLE` muestra un error comprensible y permite reintentar sin afirmar éxito.
- El mapa usa Leaflet y teselas de OpenStreetMap (sin clave API); necesita conexión a Internet para las teselas y conserva su atribución.
- El backend no ofrece listado inicial de vehículos. El dashboard descubre vehículos mediante SSE desde que se abre la sesión y permite buscar un ID conocido con el endpoint de estado; no puede enumerar telemetría previa sin conocer los IDs. Las informaciones operativas son volátiles y el centro de control solo muestra las condiciones activas que conoce el backend en memoria.
- No hay endpoint de salud: el estado de backend se infiere de la conexión SSE y de respuestas correctas a solicitudes de estado; el estado SSE se muestra por separado. La telemetría SSE es efímera y no repone eventos anteriores al conectar.