# Pruebas de carga con k6

## Propósito

k6 permite medir el comportamiento HTTP de la API bajo una carga controlada. Este escenario mide la publicación de eventos de telemetría; no es por sí mismo una prueba de estrés, resistencia, saturación o recuperación.

## Prerrequisitos

- k6 instalado en la máquina que ejecuta la prueba. k6 es una herramienta externa; no es una dependencia Maven del backend.
- El backend disponible en la URL configurada.
- Kafka y PostgreSQL en ejecución, ya que el endpoint publica en Kafka y el consumidor persiste los eventos en PostgreSQL.

Para el entorno local documentado en el README principal, Kafka y PostgreSQL se inician con Docker Compose y el backend se ejecuta en el host.

## Ubicación del script

El escenario se encuentra en [`telemetry.js`](./telemetry.js). Envía solicitudes al endpoint `POST /api/telemetry`, usando el contrato real de la API.

## Configuración

Los valores corresponden a los valores predeterminados de `telemetry.js`, excepto `RUN_ID`, que es obligatorio:

| Variable | Valor por defecto | Uso |
|---|---:|---|
| `BASE_URL` | `http://localhost:8080` | URL base del backend. |
| `RUN_ID` | Sin valor; obligatorio | Identificador de la ejecución, incorporado al prefijo de `vehicleId` como `k6-<RUN_ID>-<VU>`. Admite de 1 a 50 letras, números, guiones y guiones bajos. |
| `MAX_VUS` | `1` | Máximo de usuarios virtuales. Debe ser un entero positivo. |
| `STAGE_SECONDS` | `10` | Duración de cada etapa de rampa y de la etapa final al máximo. Debe ser positivo. |
| `THINK_TIME_SECONDS` | `1` | Pausa entre solicitudes de cada VU. Debe ser cero o positiva. |
| `P95_MAX_MS` | `1500` | Umbral máximo exclusivo para el percentil 95 de `http_req_duration`, en milisegundos. |
| `CHECK_FAILURE_RATE_MAX` | `0` | Tasa máxima admitida de checks fallidos, como proporción entre `0` y `1`. Con `0`, todos los POST deben responder `202`. |

El escenario empieza con 1 VU y usa etapas progresivas hasta `MAX_VUS`, con escalones intermedios de 5, 10, 20, 50, 100, 200, 500 y 1000 cuando no superan el máximo configurado. La etapa final mantiene el máximo configurado durante `STAGE_SECONDS`. El número de VUs no representa una tasa fija de solicitudes por segundo: la tasa observada también depende de la latencia HTTP y de la pausa entre iteraciones.

Cada POST recibe un UUID nuevo como `eventId`; cada VU usa un `vehicleId` derivado del `RUN_ID`. El cuerpo también incluye latitud, longitud, timestamp ISO-8601 actual y `speed: 45` km/h. Esta velocidad reduce la posibilidad de activar la regla de vehículo detenido; no sustituye una prueba funcional independiente de alertas.

## Ejecución en PowerShell

Con las dependencias disponibles y el backend iniciado, desde la raíz del repositorio:

```powershell
$env:BASE_URL = "http://localhost:8080"
$env:RUN_ID = "loadtest-$(Get-Date -Format yyyyMMdd-HHmmss)"
$env:MAX_VUS = "10"
$env:STAGE_SECONDS = "10"
$env:THINK_TIME_SECONDS = "1"
$env:P95_MAX_MS = "1500"
$env:CHECK_FAILURE_RATE_MAX = "0"

k6 run .\load\k6\telemetry.js
```

Para cada ejecución, asigna un `RUN_ID` nuevo de modo que sus vehículos puedan distinguirse de otras ejecuciones. Mantén `CHECK_FAILURE_RATE_MAX=0` para exigir que todos los checks de status `202` tengan éxito; aumentarlo permite checks fallidos y relaja ese criterio. Los thresholds son criterios de ensayo configurables, no garantías de rendimiento.

## Qué mide la prueba

- `http_req_duration`: duración observada de las solicitudes HTTP, incluida la espera que hace el endpoint al publicar en Kafka.
- Check `POST /api/telemetry returns 202`: comprueba el status HTTP de cada respuesta.
- `http_req_failed`: métrica incorporada de solicitudes HTTP fallidas que k6 muestra en sus resultados.
- `telemetry_posts_accepted`: contador del script incrementado por cada respuesta `202`.
- `telemetry_http_errors`: contador del script incrementado cuando la respuesta no es `202`.

El script define tres thresholds: p95 de `http_req_duration`, tasa de éxito de checks y al menos un POST aceptado. Con `CHECK_FAILURE_RATE_MAX=0`, el threshold de checks requiere una tasa de éxito del 100 %: cualquier respuesta distinta de `202` incumple ese criterio y hace fallar la ejecución. El threshold de al menos un POST evita aprobar una ejecución sin muestras aceptadas. Los checks fallidos y `http_req_failed` no son la misma medición: el check evalúa el contrato exacto `202`, mientras que `http_req_failed` es la métrica HTTP incorporada de k6. Los contadores `telemetry_posts_accepted` y `telemetry_http_errors` permiten ver respectivamente las respuestas aceptadas y las no aceptadas en los resultados.

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
