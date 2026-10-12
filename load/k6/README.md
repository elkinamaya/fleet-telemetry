# Pruebas de carga con k6

## Propósito y alcance

k6 mide el comportamiento HTTP de publicación de telemetría con carga controlada. El escenario mezcla solicitudes originales válidas, reenvíos duplicados y solicitudes inválidas intencionales. No es por sí solo una prueba de estrés, resistencia, recuperación ni disponibilidad productiva.

## Prerrequisitos

- k6 instalado en la máquina que ejecuta la prueba; es una herramienta externa, no una dependencia Maven del backend.
- Backend disponible en la URL configurada.
- Kafka y PostgreSQL en ejecución; el endpoint publica en Kafka y el consumidor persiste en PostgreSQL.

En el entorno local documentado por el README principal, Kafka y PostgreSQL se inician con Docker Compose y Spring Boot se ejecuta en el host.

## Escenario y contrato

[`telemetry.js`](./telemetry.js) envía solicitudes a `POST /api/telemetry`.

- Solicitud original válida: `eventId` UUID nuevo, `vehicleId` `k6-<RUN_ID>-<VU>`, coordenadas y timestamp ISO-8601 actuales, `speed: 45` km/h. Se espera `202`.
- Duplicado: reutiliza exactamente el mismo objeto JSON (incluidos `eventId`, timestamp y payload) de una solicitud original válida de ese mismo VU/ciclo. Se espera `202`; el endpoint vuelve a publicar, y PostgreSQL debe conservar una sola fila por `eventId`.
- Error intencional: cambia `latitude` a `91`, fuera del rango real de `TelemetryRequest` (`-90` a `90`). El backend responde `400` con código Problem Details `REQUEST_VALIDATION_FAILED`; esa respuesta se registra como el resultado esperado, no como un fallo del servicio.

El script clasifica como inesperado cualquier status diferente del contrato correspondiente, un error de transporte/status `0`, una solicitud inválida aceptada (`202`) o una respuesta `400` que no contenga el código de validación esperado. Un error inesperado de una solicitud inválida cuenta como fallo del sistema, igual que el de una solicitud original o duplicada. El endpoint y su contrato de producción no se modifican.

## Porcentajes y reproducibilidad

`DUPLICATE_PERCENT` (predeterminado `10`) e `INTENTIONAL_ERROR_PERCENT` (predeterminado `5`) son porcentajes enteros del total de solicitudes HTTP. Cada VU asigna sus iteraciones a ciclos locales deterministas de 100 solicitudes: primero originales válidas, luego el porcentaje duplicado y, al final, el porcentaje inválido. Con los valores predeterminados, cada ciclo completo contiene 85 originales únicas, 10 reenvíos del mismo payload original del ciclo y 5 solicitudes inválidas.

La secuencia es local a cada VU, por lo que no depende de memoria compartida ni de la carrera entre VUs. Los porcentajes son exactos para ciclos completos de cada VU; el ramping puede terminar VUs en ciclos parciales y producir una desviación agregada pequeña o importante en ejecuciones cortas. Las métricas de tasas informan la proporción realmente observada y sus thresholds la comparan con los objetivos dentro de `TRAFFIC_RATE_TOLERANCE` (predeterminado 0.02, es decir, ±2 puntos porcentuales). Si la ejecución no completa suficientes ciclos, los thresholds fallan en vez de declarar cumplido el porcentaje. Usa la salida de k6 para registrar los conteos y tasas efectivos, no solo la configuración.

Cada VU representa un vehículo identificable durante una ejecución; cientos de VUs no equivalen a una tasa fija de solicitudes por segundo. La tasa real depende de latencia, pausa por iteración, CPU y capacidad del entorno.

## Configuración

| Variable | Valor predeterminado | Uso |
|---|---:|---|
| `BASE_URL` | `http://localhost:8080` | URL base del backend. |
| `RUN_ID` | Obligatorio | Prefijo aislado `k6-<RUN_ID>-<VU>`; de 1 a 50 letras, números, guiones o guiones bajos. Usa un valor nuevo por ejecución. |
| `MAX_VUS` | `1` | Máximo de usuarios/vehículos virtuales; entero positivo. |
| `STAGE_SECONDS` | `10` | Duración de cada etapa de rampa y de la meseta final. |
| `THINK_TIME_SECONDS` | `1` | Pausa entre solicitudes de cada VU; puede ser cero. |
| `DUPLICATE_PERCENT` | `10` | Porcentaje entero de solicitudes que reenvía el payload original. |
| `INTENTIONAL_ERROR_PERCENT` | `5` | Porcentaje entero de solicitudes con latitud inválida. Debe sumar menos de 100 junto con el porcentaje de duplicados. |
| `TRAFFIC_RATE_TOLERANCE` | `0.02` | Tolerancia absoluta entre la tasa observada y cada porcentaje objetivo, entre 0 y 1. |
| `P95_MAX_MS` | `1500` | Umbral superior exclusivo de p95 para `http_req_duration`, en milisegundos. |
| `CHECK_FAILURE_RATE_MAX` | `0` | Tasa máxima de checks fallidos, como proporción entre 0 y 1. Un check pasa si se observó el status/código esperado, incluidos los `400` intencionales correctos. |
| `UNEXPECTED_FAILURE_RATE_MAX` | `0` | Tasa máxima admitida de fallos inesperados; predeterminado estricto, sin tolerancia. |

El escenario inicia con 1 VU y escala hasta `MAX_VUS` por los niveles 5, 10, 20, 50, 100, 200, 500 y 1000 que no superen el máximo; termina con una etapa de meseta al máximo. `STAGE_SECONDS` aplica a cada nivel. Los thresholds de p95 y fiabilidad son parámetros de ensayo, no garantías de rendimiento.

## Métricas y umbrales

- `telemetry_original_requests` y `telemetry_originals_accepted`: originales válidas intentadas y aceptadas con `202`.
- `telemetry_duplicate_requests` y `telemetry_duplicates_accepted`: reenvíos intentados y confirmados con `202`.
- `telemetry_intentional_invalid_requests` y `telemetry_expected_validation_errors`: inválidas intencionales y respuestas `400 REQUEST_VALIDATION_FAILED` esperadas.
- `telemetry_validation_scenario_failures`: solicitud inválida aceptada (`202`) o rechazada con `400` sin el código esperado.
- `telemetry_unexpected_failures` y `telemetry_unexpected_failure_rate`: fallos de contrato/servidor/transportes inesperados, también cuando afectan una solicitud inválida.
- `telemetry_duplicate_request_rate` y `telemetry_intentional_error_rate`: proporciones reales de cada tipo sobre las solicitudes ejecutadas.
- `telemetry_posts_accepted` y `telemetry_http_errors`: respectivamente total de respuestas `202` y total de respuestas no `202`; este último incluye los `400` intencionales esperados.
- `http_req_duration`, `http_req_failed` y `checks`: métricas k6 estándar. `http_req_failed` considera los `400` como fallos HTTP aunque sean las respuestas esperadas para el escenario inválido; para separar fallos del sistema, usa `telemetry_unexpected_failures`, su tasa y los checks del contrato esperado.

Los thresholds de checks, tasa de fallos inesperados, porcentajes, p95 y al menos una solicitud aceptada deben cumplirse. No eleves tolerancias ni tasas de fallo para ocultar problemas; si la variación viene de ciclos incompletos, extiende la duración de la etapa.

## Prueba pequeña inicial en PowerShell

Con la infraestructura local iniciada y el backend accesible, ejecuta desde la raíz. Una nueva ejecución necesita un `RUN_ID` único:

```powershell
$env:BASE_URL = "http://localhost:8080"
$env:RUN_ID = "smoke-$(Get-Date -Format yyyyMMdd-HHmmss)"
$env:MAX_VUS = "1"
$env:STAGE_SECONDS = "60"
$env:THINK_TIME_SECONDS = "0.1"
$env:DUPLICATE_PERCENT = "10"
$env:INTENTIONAL_ERROR_PERCENT = "5"
$env:TRAFFIC_RATE_TOLERANCE = "0.02"
$env:P95_MAX_MS = "1500"
$env:CHECK_FAILURE_RATE_MAX = "0"
$env:UNEXPECTED_FAILURE_RATE_MAX = "0"

k6 run .\load\k6\telemetry.js
```

Revisa que haya originales aceptadas, duplicados aceptados, errores intencionales coincidentes con respuestas esperadas, cero fallos inesperados y tasas dentro de tolerancia. Si los contadores de duplicados o errores esperados son cero o los thresholds de tasa fallan, la muestra fue demasiado corta; aumenta `STAGE_SECONDS` con un `RUN_ID` nuevo y repite. No uses una tasa de solicitudes por segundo inferida del número de VUs.

## Escalado progresivo

Solo aumenta la carga si el nivel anterior cumple los thresholds y el entorno está estable. Ejecuta cada bloque de forma independiente y usa un `RUN_ID` nuevo en cada corrida:

```powershell
$env:RUN_ID = "load50-$(Get-Date -Format yyyyMMdd-HHmmss)"
$env:MAX_VUS = "50"
$env:STAGE_SECONDS = "15"
k6 run .\load\k6\telemetry.js
```

Después de revisar la salida y la persistencia, escala gradualmente:

```powershell
$env:RUN_ID = "load100-$(Get-Date -Format yyyyMMdd-HHmmss)"
$env:MAX_VUS = "100"
k6 run .\load\k6\telemetry.js
```

```powershell
$env:RUN_ID = "load200-$(Get-Date -Format yyyyMMdd-HHmmss)"
$env:MAX_VUS = "200"
k6 run .\load\k6\telemetry.js
```

```powershell
$env:RUN_ID = "load500-$(Get-Date -Format yyyyMMdd-HHmmss)"
$env:MAX_VUS = "500"
k6 run .\load\k6\telemetry.js
```

No avances al siguiente nivel si hay fallos inesperados, thresholds incumplidos, retraso acumulado del consumidor o recursos saturados. Ajusta la duración explícitamente y conserva la configuración y salida de cada ejecución.

## Comprobar persistencia e idempotencia en PostgreSQL

El esquema real define `telemetry_events.event_id` como clave primaria y el repositorio inserta con `ON CONFLICT (event_id) DO NOTHING`. Los reenvíos duplicados pueden producir más de una publicación Kafka, pero la clave primaria evita más de una fila por ID. `202 Accepted` confirma la publicación en Kafka, no la persistencia.

Tras terminar k6, espera a que el consumidor procese los mensajes pendientes. Desde PowerShell, conserva exactamente el `RUN_ID` utilizado:

```powershell
$runId = $env:RUN_ID
docker compose exec db psql -U fleet -d fleet -c "SELECT count(*) AS persisted_rows, count(DISTINCT event_id) AS distinct_event_ids, count(*) - count(DISTINCT event_id) AS duplicate_rows FROM telemetry_events WHERE left(vehicle_id, length('k6-$runId-')) = 'k6-$runId-';"
```

En una ejecución sin fallos inesperados, `persisted_rows` debe igualar `distinct_event_ids`, `duplicate_rows` debe ser 0 y el total debe coincidir con `telemetry_originals_accepted` de k6. Las 10 % solicitudes duplicadas no deberían añadir filas. Las solicitudes inválidas no se publican ni persisten. Si hubo errores de publicación o respuesta ambigua, el número persistido puede diferir de los originales aceptados; investiga en vez de atribuir automáticamente la diferencia a duplicados.

La siguiente consulta debe devolver cero filas; permite detectar visualmente cualquier duplicidad por ID dentro del prefijo de la ejecución:

```powershell
docker compose exec db psql -U fleet -d fleet -c "SELECT event_id, count(*) AS rows_per_event FROM telemetry_events WHERE left(vehicle_id, length('k6-$runId-')) = 'k6-$runId-' GROUP BY event_id HAVING count(*) > 1;"
```

La columna `event_id` es una clave primaria, por lo que filas duplicadas con ese ID están además impedidas por el esquema. Esta verificación posterior en PostgreSQL es independiente de las métricas HTTP de k6 y solo es válida después de permitir que Kafka sea consumido.

## Resultados históricos

Las ejecuciones que se presentan a continuación corresponden a versiones anteriores del escenario, sin inyección configurable de duplicados ni solicitudes inválidas. Se conservan como evidencia histórica y no como resultados de la nueva prueba. La modificación del script no ejecuta carga ni actualiza resultados.

## Primera prueba baseline

Los siguientes resultados corresponden a la ejecución reportada para el 12 de octubre de 2026. Se registran como resultados observados de esa ejecución, no como una garantía reproducible en otras máquinas.

| Campo | Resultado |
|---|---|
| Fecha | 12 de octubre de 2026 |
| Identificador | `baseline-20261012` |
| Script | `load/k6/telemetry.js` |
| Endpoint | `POST /api/telemetry` |
| URL | `http://localhost:8080` |
| `MAX_VUS` | `10` |
| `STAGE_SECONDS` | `10` |
| `THINK_TIME_SECONDS` | `1` |
| `P95_MAX_MS` | `1500` |
| `CHECK_FAILURE_RATE_MAX` | `0.01` |
| Duración observada | Aproximadamente 30,9 segundos |
| Iteraciones completadas | 195 |
| Solicitudes HTTP | 195 |
| Respuestas HTTP 202 | 195 |
| Checks satisfactorios | 195 de 195 (100 %) |
| Checks fallidos | 0 |
| `http_req_failed` | 0 % |
| Latencia media | 19,54 ms |
| Latencia mediana | 16,89 ms |
| Percentil 90 | 25,66 ms |
| Percentil 95 | 28,21 ms |
| Latencia máxima observada | 346,99 ms |
| Tasa de solicitudes | Aproximadamente 6,31 por segundo |
| Thresholds del script | Los tres cumplidos |

La baseline anterior conserva su configuración histórica: admitía hasta `0.01` de tasa de checks fallidos, aunque en esa ejecución los 195 checks tuvieron éxito. La configuración predeterminada del script se cambió después a cero para que las ejecuciones nuevas exijan todas las respuestas `202`.

## Segunda prueba controlada

La segunda prueba utilizó `speed: 45` km/h y tolerancia cero para checks fallidos. Resultados reportados:

| Campo | Resultado |
|---|---|
| Identificador | `loadtest-20261012-a` |
| Solicitudes HTTP | 195 |
| Respuestas HTTP 202 | 195 |
| Fallos HTTP | 0 % |
| Percentil 95 | 26,51 ms |
| Registros comprobados en PostgreSQL | 195 |

Estos resultados se mantienen separados de la baseline anterior. La baseline registró p95 de 28,21 ms; esta segunda ejecución registró 26,51 ms. Son mediciones locales de ejecuciones distintas, no una garantía de mejora de rendimiento.

Desde la raíz del repositorio en PowerShell, con el backend, Kafka y PostgreSQL disponibles:

```powershell
$env:BASE_URL = "http://localhost:8080"
$env:RUN_ID = "loadtest-20261012-a"
$env:MAX_VUS = "10"
$env:STAGE_SECONDS = "10"
$env:THINK_TIME_SECONDS = "1"
$env:P95_MAX_MS = "1500"
$env:CHECK_FAILURE_RATE_MAX = "0"

k6 run .\load\k6\telemetry.js
```

Con `MAX_VUS=10` las etapas son 1→5 durante 10 s, 5→10 durante 10 s y una etapa final que mantiene 10 VUs durante 10 s: aproximadamente 30 s de escenario, sin contar el cierre gradual. La ejecución falla si el p95 no queda por debajo de 1500 ms, si cualquier check de status `202` falla o si no se registra al menos una solicitud aceptada.

Después de la ejecución y de permitir que el consumidor procese los eventos, la cantidad asociada a este `RUN_ID` se puede comprobar manualmente en DBeaver:

```sql
SELECT count(*)
FROM telemetry_events
WHERE vehicle_id LIKE '%loadtest-20261012-a%';
```

La consulta confirma filas persistidas, no es una métrica automática de k6. El status `202` confirma la publicación según el contrato HTTP actual, no persistencia inmediata.

## Tercera prueba progresiva hasta 20 VUs

Resultados reportados para la ejecución progresiva hasta 20 usuarios virtuales:

| Campo | Resultado |
|---|---|
| Identificador (`RUN_ID`) | Pendiente de confirmar; `loadtest-20261012-b` figuraba como propuesta, no como identificador confirmado de esta ejecución |
| Máximo de VUs | 20 |
| Duración aproximada | 61 segundos |
| Solicitudes HTTP | 662 |
| Checks satisfactorios | 662 de 662 (100 %) |
| Fallos HTTP | 0 % |
| Latencia media | 16,43 ms |
| Mediana | 16,98 ms |
| Percentil 90 | 23,2 ms |
| Percentil 95 | 24,16 ms |
| Latencia máxima observada | 34,83 ms |
| Tasa observada | Aproximadamente 10,85 solicitudes por segundo |
| Registros comprobados manualmente en PostgreSQL | 662 |

La configuración documentada para este ensayo fue `STAGE_SECONDS=15`, `THINK_TIME_SECONDS=1`, `P95_MAX_MS=1500` y `CHECK_FAILURE_RATE_MAX=0`, con `speed: 45` km/h en el payload. Para 20 VUs, el escenario tiene las etapas 1→5, 5→10, 10→20 y una etapa final que mantiene 20 VUs. A 15 segundos por etapa, son 45 segundos de rampa y 15 segundos de meseta (60 segundos de escenario, sin contar el cierre gradual); la duración observada reportada fue aproximadamente 61 segundos.

Los 662 registros se comprobaron manualmente en PostgreSQL después de la ejecución. Una vez confirmado el `RUN_ID` realmente usado, puede verificarse en DBeaver con esta consulta, sustituyendo `<RUN_ID>`:

```sql
SELECT count(*)
FROM telemetry_events
WHERE vehicle_id LIKE '%<RUN_ID>%';
```

El conteo SQL es una comprobación posterior e independiente: k6 no midió automáticamente la persistencia en PostgreSQL.

### Verificación manual de persistencia

Después de la baseline, se consultó PostgreSQL manualmente mediante DBeaver y se encontraron exactamente 195 registros asociados al identificador de ejecución:

```sql
SELECT *
FROM telemetry_events
WHERE vehicle_id LIKE '%baseline-20261012%'
ORDER BY event_timestamp DESC;
```

Esta consulta fue una verificación manual posterior; el conteo de filas no es una métrica medida automáticamente por k6. Las pruebas 2 y 3 también tuvieron sus conteos comprobados manualmente después de ejecutarse.

## Interpretación y limitaciones

El status HTTP `202 Accepted` indica que la API aceptó la publicación del evento según su contrato actual. No confirma por sí solo que el consumidor ya lo haya persistido en PostgreSQL. Las consultas manuales posteriores comprobaron que las tres ejecuciones tuvieron 195, 195 y 662 filas respectivamente. No son mediciones de latencia de persistencia.

## Conclusión de esta fase

En las tres ejecuciones reportadas, todos los checks de respuesta fueron satisfactorios, no se registraron fallos HTTP y los conteos manuales posteriores en PostgreSQL coincidieron con las solicitudes: 195, 195 y 662. La tercera prueba aplicó una rampa de hasta 20 VUs con una meseta final de 20 VUs. Estos resultados sirven como observaciones de las ejecuciones locales realizadas; no establecen capacidad máxima ni garantizan el rendimiento en producción.

## Limitaciones y futuras pruebas

Las pruebas se realizaron en un entorno local y no demuestran capacidad máxima ni rendimiento en producción. Miden principalmente el tiempo de respuesta HTTP de publicación, no la latencia completa desde la solicitud hasta la persistencia. En estas ejecuciones no se midieron métricas detalladas de CPU, memoria, latencia de Kafka, retraso del consumidor ni comportamiento ante fallos. El payload usa `speed: 45` km/h para reducir la posibilidad de activar la regla de vehículo detenido; las alertas deben validarse en pruebas funcionales separadas.

Como continuación, confirmar y registrar el `RUN_ID` de la tercera ejecución, conservar la configuración exacta usada en próximas corridas, y añadir métricas de recursos/lag y escenarios específicos de fallos o recuperación antes de extraer conclusiones sobre esos aspectos.

## Registro de futuras pruebas

| Ejecución | Fecha | `MAX_VUS` | Solicitudes | Fallos HTTP | p95 | Registros verificados | Observaciones |
|---|---|---:|---:|---:|---:|---:|---|
| `baseline-20261012` | 2026-10-12 | 10 | 195 | 0 | 28,21 ms | 195 | Umbrales cumplidos |
| `loadtest-20261012-a` | 2026-10-12 | 10 | 195 | 0 % | 26,51 ms | 195 | Resultados reportados; umbrales cumplidos |
| Pendiente de confirmar | Pendiente de confirmar | 20 | 662 | 0 % | 24,16 ms | 662 | Prueba progresiva; resultados reportados |
