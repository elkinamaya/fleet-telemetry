# Fleet Telemetry móvil

Aplicación independiente para conductores, desarrollada con Expo, React Native, TypeScript y Expo SQLite. Captura telemetría localmente y ofrece una acción explícita en primer plano para enviar eventos pendientes al endpoint batch del backend.

## Instalación y ejecución en Windows

Desde PowerShell:

```powershell
Set-Location .\mobile
npm ci
npx expo install --check
npx expo start
```

Para usar un emulador Android, inícialo primero desde Android Studio y luego ejecuta:

```powershell
npx expo start --android
```

Para un dispositivo Android físico, instala una versión compatible de Expo Go y conecta el teléfono y el equipo de desarrollo a la misma red local. Escanea el código QR del servidor de desarrollo de Expo. Tanto Expo Go como el teléfono deben poder acceder al servidor Metro y al backend.

## Configuración del vehículo y el backend

Copia `.env.example` a `.env.local`, configura `EXPO_PUBLIC_VEHICLE_ID` para el vehículo de demostración y establece `EXPO_PUBLIC_API_BASE_URL` con la URL base del backend. El identificador del vehículo también se puede editar en la app. Los valores `EXPO_PUBLIC_` se incluyen en el cliente; nunca pongas credenciales ni secretos en ellos. Reinicia Expo después de cambiar el archivo de entorno.

Configura la URL base según dónde se ejecuten la app y el backend:

| Entorno | Ejemplo de URL base |
| --- | --- |
| App y backend en el mismo PC (web/desarrollo local) | `http://localhost:8080` |
| Emulador Android y backend en el PC de desarrollo | `http://10.0.2.2:8080` |
| Teléfono Android físico y backend en el PC de desarrollo | `http://<IP-LAN-DEL-PC>:8080` |

En un teléfono, `localhost` se refiere al propio teléfono, no al equipo de desarrollo. Para un teléfono físico, usa una dirección LAN accesible del PC, comprueba que el backend escuche en una interfaz accesible y permite el puerto del backend en el firewall local. No expongas un backend local sin protección a redes no confiables.

La app envía solicitudes a `{EXPO_PUBLIC_API_BASE_URL}/api/telemetry/batch`. El backend acepta entre 1 y 100 identificadores de evento únicos por solicitud. La app valida que la URL base configurada use HTTP(S), establece un tiempo de espera de 10 segundos y solo envía `eventId`, `vehicleId`, `latitude`, `longitude`, `timestamp` y `speed` cuando está disponible. `source`, los metadatos de reintento y las demás columnas SQLite locales no se envían.

## Captura local y sincronización manual

- La captura GPS obtiene una ubicación puntual en primer plano cuando el conductor la solicita. No hay seguimiento continuo ni tareas en segundo plano.
- Expo Location entrega la velocidad GPS en metros por segundo; la app la convierte a kilómetros por hora sin redondearla. Si no hay medición válida, la velocidad del evento es `null`.
- Las coordenadas introducidas manualmente son valores de prueba y se identifican como simulados. El campo opcional de velocidad manual también es simulado y está expresado en km/h; admite cero y queda como `null` si se deja vacío.
- Cada captura recibe un UUID y un timestamp ISO en UTC, y se guarda en `fleet-telemetry.db` con estado `pending`. La sincronización no cambia el UUID ni el payload de telemetría almacenado.
- Pulsa **Sincronizar ahora** para enviar eventos pendientes en orden de creación, en lotes de hasta 100. Se evitan intentos de sincronización simultáneos. No hay reintentos periódicos automáticos ni solicitudes en segundo plano.
- Una respuesta `202 Accepted` válida confirma el lote solo cuando cada ID solicitado tiene un resultado `PUBLISHED` correspondiente.
- Una respuesta `207 Multi-Status` válida confirma únicamente las entradas `PUBLISHED`; las entradas `PUBLICATION_UNCONFIRMED` permanecen pendientes.
- `PUBLICATION_UNCONFIRMED` significa que el backend no pudo confirmar la publicación en Kafka. El cliente móvil mantiene el evento pendiente y registra un código de error sanitizado.
- `PUBLISHED` significa que Kafka confirmó la publicación. **No** significa que PostgreSQL/TimescaleDB ya haya persistido el evento.
- Los eventos solo se marcan `synced` después de validar una respuesta asociada por `eventId`. IDs o estados inválidos, ausentes, duplicados o inesperados no constituyen confirmación suficiente.
- Los errores HTTP, tiempos de espera, pérdida de conexión y respuestas malformadas dejan los eventos no confirmados como pendientes y guardan únicamente un código de error sanitizado. No se almacenan cuerpos de error, encabezados ni credenciales.

### Resultados ambiguos

Si el teléfono pierde conexión o vence el tiempo de espera, el backend podría haber recibido o publicado algunos eventos aunque la app no recibiera la respuesta. Esos eventos permanecen pendientes porque la app carece de confirmación. Un envío manual posterior conserva sus IDs y payloads originales, pero puede publicar eventos repetidos en Kafka. El ID estable del backend permite persistencia idempotente; no garantiza publicación exactamente una vez en Kafka ni demuestra persistencia a partir de la respuesta móvil.

## Pruebas y comprobaciones

Ejecuta desde `mobile/`:

```powershell
npm test
npx tsc --noEmit
npm run lint
npx expo install --check
```

Las pruebas simulan HTTP y usan dobles SQLite en memoria para el comportamiento del almacenamiento y el servicio. No ejercitan el motor SQLite nativo, la pila de red Android ni un backend activo. Si Expo Doctor está instalado en el entorno del proyecto, ejecuta también `npx expo-doctor` como diagnóstico adicional.

## Comprobación manual de integración en Android

1. Inicia el backend con el comando local documentado en el repositorio y comprueba que escucha en la interfaz y el puerto esperados.
2. Configura `.env.local` para el emulador (`10.0.2.2`) o el teléfono (IP LAN del PC), reinicia Expo e inicia la app.
3. Registra un evento simulado y uno GPS; comprueba que ambos aparecen como pendientes y se conservan al cerrar y volver a abrir la app.
4. Pulsa **Sincronizar ahora** con el backend accesible; comprueba que el contador de confirmados aumenta y las tarjetas muestran `PUBLISHED · KAFKA`.
5. Para comprobar los fallos, vuelve temporalmente inaccesible el backend, pulsa sincronizar y verifica que los eventos siguen pendientes con un error sanitizado. Restaura la conectividad y vuelve a usar el botón manual. Este reintento puede volver a publicar un evento si el resultado del primer intento fue ambiguo.
6. Para verificar persistencia de extremo a extremo, consulta PostgreSQL por los IDs capturados después del consumo de Kafka. No consideres la respuesta móvil `PUBLISHED` como prueba de persistencia en la base de datos.

La precisión del GPS nativo, el comportamiento sin conexión en Android, la compatibilidad con un backend activo, la publicación en Kafka y la persistencia eventual en la base de datos requieren esta comprobación manual/en dispositivo; las pruebas automatizadas no los demuestran.

## Tokens visuales

La pantalla nativa adapta los tokens del frontend web definidos en `frontend/src/index.css` a `src/theme.ts`: fondo `#101619`, panel `#1b272d`, tarjeta `#223239`, superficie elevada `#293b42`, acento turquesa `#35d6cb`, azul `#4b9ddb`, éxito `#42c98a`, advertencia `#f2b84b`, error `#f16b72` y los mismos valores de texto primario, secundario y atenuado. La app web define la marca mediante CSS, no como un recurso móvil independiente; el encabezado móvil recrea sus tres barras con vistas nativas. No se importa ninguna hoja de estilos ni dependencia visual web. `react-native-safe-area-context` se usa únicamente para los márgenes seguros del dispositivo y es compatible con este SDK de Expo en Expo Go.
