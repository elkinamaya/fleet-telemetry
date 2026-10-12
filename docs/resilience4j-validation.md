# Validación técnica del Circuit Breaker de Groq

## Objetivo y alcance

El Circuit Breaker `groq` protege únicamente la llamada HTTP saliente al proveedor compatible con Groq dentro de `OpenAiChatCompletionAdapter`: envío de la solicitud y lectura/procesamiento de la respuesta remota. La ausencia de API key y la preparación local del request ocurren fuera del circuito. La ejecución de herramientas, las consultas de telemetría y los controladores tampoco están protegidos por este breaker.

No se añade Retry. Los timeouts HTTP configurados en el adaptador permanecen en 3 segundos para conexión y 20 segundos para lectura.

## Estados

- **CLOSED:** las llamadas se permiten. El circuito agrega los resultados contabilizables a la ventana configurada. En esta integración, se contabilizan como fallos los errores de acceso/conexión, respuestas HTTP 5xx y respuestas remotas malformadas.
- **OPEN:** nuevas llamadas se rechazan sin ejecutar la petición HTTP. El adaptador convierte el rechazo en `AiProviderUnavailableException`, que el manejador HTTP existente traduce a `503 AI_PROVIDER_UNAVAILABLE`.
- **HALF_OPEN:** terminado el periodo OPEN, el siguiente intento puede pasar el circuito a HALF_OPEN y se admiten como máximo las llamadas de prueba configuradas. Si sus resultados son satisfactorios, el circuito vuelve a CLOSED.

Las respuestas HTTP 4xx, incluida 429, se excluyen del cálculo del circuito y no se contabilizan como éxitos. Mantienen el error público actual `503 AI_PROVIDER_UNAVAILABLE`.

## Configuración efectiva

La instancia se crea con el nombre `groq` en `GroqCircuitBreakerConfiguration`. `application.properties` permite cambiar estos valores mediante variables de entorno; los valores entre paréntesis son los predeterminados actuales.

| Parámetro | Valor | Significado |
|---|---:|---|
| Tipo de ventana | `COUNT_BASED` | Evalúa una cantidad de llamadas, no una ventana de tiempo. |
| `slidingWindowSize` | 10 | Máximo de resultados contabilizables retenidos en la ventana. |
| `minimumNumberOfCalls` | 5 | Llamadas contabilizadas requeridas antes de evaluar la tasa de fallos. |
| `failureRateThreshold` | 50 % | Tasa que activa la apertura una vez alcanzado el mínimo. |
| `waitDurationInOpenState` | 15 s | Tiempo mínimo que permanece OPEN antes de permitir la transición al probar otra llamada. |
| `permittedNumberOfCallsInHalfOpenState` | 2 | Número de llamadas de prueba permitido en HALF_OPEN. |
| `automaticTransitionFromOpenToHalfOpenEnabled` | `false` | No cambia a HALF_OPEN por sí solo; espera a que llegue una llamada cuando expire el periodo OPEN. |

Variables de entorno: `AI_GROQ_CB_SLIDING_WINDOW_SIZE`, `AI_GROQ_CB_MINIMUM_NUMBER_OF_CALLS`, `AI_GROQ_CB_FAILURE_RATE_THRESHOLD`, `AI_GROQ_CB_WAIT_DURATION_IN_OPEN_STATE_SECONDS`, `AI_GROQ_CB_PERMITTED_CALLS_IN_HALF_OPEN_STATE` y `AI_GROQ_CB_AUTOMATIC_TRANSITION`.

## Pruebas y resultados observados

El archivo `OpenAiChatCompletionAdapterTest` contiene estos métodos:

| Método de prueba | Qué comprueba |
|---|---|
| `sendsToolCallingRequestToConfiguredProvider` | Forma de la solicitud con herramientas y extracción de la llamada a herramienta desde la respuesta. |
| `opensAfterFiveTransientFailuresAndClosesAfterTwoHalfOpenSuccesses` | Cinco 503 consecutivos abren el circuito; un intento mientras está OPEN se rechaza; dos respuestas satisfactorias tras el periodo de prueba permiten HALF_OPEN y CLOSED. |
| `successfulProviderResponsesKeepCircuitClosed` | Dos respuestas satisfactorias se registran como éxitos y el circuito sigue CLOSED. |
| `countsConnectionTimeoutAsTransientFailure` | Un fallo de acceso simulado se cuenta como un fallo transitorio, no como éxito. |
| `ignoresProviderClientErrorsWithoutRecordingSuccessOrFailure` | HTTP 400, 401, 403 y 429 no se contabilizan como éxito ni como fallo y no abren el circuito. |
| `ignoresMissingKeyLocalValidationAndTelemetryQueryFailures` | Una clave vacía y errores locales/de consulta no incrementan los contadores. La parte de consulta ejecuta una excepción `AiTelemetryQueryException` mediante el circuito; no simula una consulta real a PostgreSQL. |
| `countsMalformedProviderResponseAsTransientFailure` | Una respuesta remota JSON malformada incrementa el contador de fallos transitorios. |
| `sendsToolCallAndToolResultMessagesWithMatchingToolCallId` | La llamada a herramienta y el resultado se envían en solicitudes consecutivas con el mismo identificador de llamada. |
| `doesNotExposeApiKeyOrProviderBodyWhenProviderFails` | La respuesta pública y los logs no exponen la API key ni detalles sensibles ante un HTTP 400. |
| `reportsMissingApiKeyWithoutMakingAProviderRequest` | La falta de API key produce el error de configuración esperado antes de una petición remota. |

Los tests que envían solicitudes usan `MockRestServiceServer`, vinculado al `RestClient.Builder`, y el host simulado `provider.example`; sus respuestas son configuradas localmente en cada test. En `opensAfterFiveTransientFailuresAndClosesAfterTwoHalfOpenSuccesses` hay expectativas para cinco respuestas 503 y dos respuestas satisfactorias. El intento adicional mientras el breaker está OPEN debe fallar inmediatamente; si intentara efectuar otra petición, el servidor simulado no tiene una respuesta esperada adicional y el test falla. La llamada también se comprueba por la excepción producida mientras el circuito está abierto.

La prueba HALF_OPEN **no espera los 15 segundos de producción**: usa una configuración independiente con `waitDurationInOpenState` de 25 ms y duerme 50 ms antes de la primera llamada de prueba. No es una medición de latencia ni una prueba con el temporizador de producción.

`GroqCircuitBreakerConfiguration` no tiene actualmente una clase de test dedicada que compruebe todos los valores predeterminados. `TelemetryApplicationTests` arranca el contexto de aplicación, pero el reporte por sí solo no demuestra cada valor individual de configuración.

`AiChatControllerTest` aporta cobertura HTTP relacionada:

| Método de prueba | Qué comprueba |
|---|---|
| `returnsAgentAnswerAndToolMetadata` | Respuesta normal del agente y metadatos de herramientas. |
| `rejectsBlankAndOversizedQuestions` | Validación HTTP y que el caso de uso no se invoca para esos requests. |
| `mapsProviderFailureToSafeProblemDetail` | `AiProviderUnavailableException` se traduce a HTTP 503 `AI_PROVIDER_UNAVAILABLE` sin exponer el mensaje interno. |
| `mapsTelemetryQueryFailureDifferentlyFromMissingVehicle` | Un fallo de consulta se mantiene diferenciado como `AI_TELEMETRY_QUERY_UNAVAILABLE`. |

El test del controlador simula la excepción del caso de uso; no ejecuta la secuencia real `CallNotPermittedException` → adaptador → controller. Por tanto, el error generado por el adaptador y el contrato HTTP de `AiProviderUnavailableException` están cubiertos por separado, pero el flujo combinado no tiene una prueba de extremo a extremo dedicada.

Las pruebas mostradas no configuran ni usan credenciales reales. Las solicitudes usan un servidor HTTP simulado; los casos de ausencia de clave no emiten solicitudes. No hay evidencia en estos tests ni en los reportes citados de una llamada real a Groq.

### Matriz de escenarios

Los resultados de la columna «Observado» corresponden a los reportes Surefire existentes de la última ejecución completa registrada, no a una ejecución realizada al crear este documento.

| Escenario | Resultado esperado | Prueba que lo verifica | Resultado observado |
|---|---|---|---|
| Respuestas correctas repetidas | El breaker permanece CLOSED y registra éxitos. | `successfulProviderResponsesKeepCircuitClosed` | Pasó en el reporte de adaptador. |
| Cinco fallos HTTP 503 | El circuito cambia de CLOSED a OPEN al alcanzar el mínimo y el umbral. | `opensAfterFiveTransientFailuresAndClosesAfterTwoHalfOpenSuccesses` | Pasó; el test observó OPEN. |
| Llamada mientras está OPEN | Se rechaza sin una petición HTTP adicional. | `opensAfterFiveTransientFailuresAndClosesAfterTwoHalfOpenSuccesses` | Pasó con el servidor simulado; las expectativas de HTTP se completaron. |
| HALF_OPEN y recuperación | Tras un wait de test de 25 ms, se aceptan dos éxitos y vuelve a CLOSED. | `opensAfterFiveTransientFailuresAndClosesAfterTwoHalfOpenSuccesses` | Pasó; el test observó HALF_OPEN y luego CLOSED. |
| Error de conexión/acceso simulado | Se contabiliza como fallo transitorio. | `countsConnectionTimeoutAsTransientFailure` | Pasó; un fallo, cero éxitos. |
| Respuesta remota malformada | Se contabiliza como fallo transitorio. | `countsMalformedProviderResponseAsTransientFailure` | Pasó; un fallo, cero éxitos. |
| HTTP 400, 401, 403 y 429 | No suma fallo ni éxito. | `ignoresProviderClientErrorsWithoutRecordingSuccessOrFailure` | Pasó; cero resultados contabilizados y circuito CLOSED. |
| Clave ausente y errores locales/de consulta | No suma fallo ni éxito al breaker. | `ignoresMissingKeyLocalValidationAndTelemetryQueryFailures` | Pasó; cero resultados contabilizados y circuito CLOSED. |
| Traducción a contrato HTTP 503 | `AiProviderUnavailableException` da `AI_PROVIDER_UNAVAILABLE`. | `mapsProviderFailureToSafeProblemDetail` | Pasó en `AiChatControllerTest`; el test simula la excepción, no el rechazo real del circuito. |
| Valores efectivos del bean de configuración | Bean `groq` usa todos los valores requeridos. | Sin test dedicado de configuración | Pendiente de validar mediante aserciones específicas; los valores se verifican en el código de configuración y propiedades. |
| Flujo real de circuito OPEN hasta respuesta HTTP | Rechazo del breaker termina en 503 `AI_PROVIDER_UNAVAILABLE`. | No hay test integrado específico | Pendiente de validar de extremo a extremo; las pruebas del adaptador y del contrato de controlador son separadas. |

## Comandos

Desde la raíz del repositorio, solo las pruebas del adaptador que cubren el Circuit Breaker:

```powershell
.\backend\mvnw.cmd -f backend\pom.xml "-Dtest=OpenAiChatCompletionAdapterTest" test
```

Esto ejecuta la clase real `backend/src/test/java/com/fleet/telemetry/infrastructure/adapter/out/ai/OpenAiChatCompletionAdapterTest.java`. Para incluir además la verificación del contrato HTTP del controlador:

```powershell
.\backend\mvnw.cmd -f backend\pom.xml "-Dtest=OpenAiChatCompletionAdapterTest,AiChatControllerTest" test
```

Para todos los tests del backend:

```powershell
.\backend\mvnw.cmd -f backend\pom.xml test
```

## Reportes disponibles

En la última ejecución completa registrada en los reportes y salida de Maven del **12 de octubre de 2026**, el backend terminó con **91 tests, 0 fallos, 0 errores y 0 omitidos; `BUILD SUCCESS`**. El reporte Surefire individual actual de `OpenAiChatCompletionAdapterTest` registra **10 tests, 0 fallos, 0 errores y 0 omitidos**, con 0,267 segundos de tiempo reportado. `AiChatControllerTest` registró 4 tests, 0 fallos, 0 errores y 0 omitidos.

No se ejecutaron tests al redactar este documento; se inspeccionaron los reportes existentes. Si cambia el código o se requiere una nueva verificación, deben repetirse los comandos anteriores.

## Tests automatizados y prueba manual contra Groq

Los tests automatizados aíslan el adaptador con `MockRestServiceServer` y respuestas controladas: permiten provocar fallos y comprobar estados sin red externa, credenciales ni consumo de cuota. No prueban disponibilidad, cuotas, políticas ni comportamiento real del proveedor.

Una prueba manual contra Groq sería una actividad distinta: requeriría credenciales configuradas, conectividad y llamadas HTTP reales, y podría consumir cuota. **No hay evidencia de que esa prueba se haya ejecutado como parte de esta validación**, y los tests automatizados no la sustituyen.

## Limitaciones y conclusión

- El estado del Circuit Breaker vive en memoria y es local a cada proceso; no se comparte ni persiste entre instancias o reinicios.
- No hay un endpoint público dedicado para consultar el estado del circuito.
- HTTP 429 se excluye actualmente del conteo de fallos. Es una decisión inicial que se puede revisar con una política de rate limiting explícita.
- La configuración exacta del bean no tiene un test dedicado que aserte los seis valores.
- Falta una prueba integrada que fuerce el circuito real a OPEN y compruebe a través del endpoint el `503 AI_PROVIDER_UNAVAILABLE`.
- No se ha comprobado mediante estos tests la capacidad bajo carga ni la disponibilidad real de Groq.

La evidencia disponible respalda las transiciones principales del circuito y la clasificación de errores en pruebas automatizadas aisladas. También respalda por separado el contrato HTTP para `AiProviderUnavailableException`. Esto cierra una validación de comportamiento del código bajo simulación, pero no permite afirmar capacidad productiva, recuperación de una caída real ni disponibilidad del proveedor.
