# Plan 6, revisión: el kiosco dentro de Berry Med, como la app del proveedor

Revisión del plan 6 (29 oct 2026). El usuario pidió hacerlo **sin modificar lo que ya funciona**: ni la API, ni el kiosco (`gvm.mt.totem`), ni la app del médico (salvo el espejado, PR aparte). Se cierran los PRs gvm.mt.api#29 y gvm.mt.totem#70.

## Cómo lo hacía el proveedor (historial de los repos)

- 19 may 2026, `gvm.mt.api` 742de503: al crear el encuentro, `redirect_url = koplak://auth?serverUrl={services.livekit.subdomain}&token={jwt}`. `koplak://` es el esquema de la app Android del proveedor.
- 20 may, 37941d90: se saca el prefijo; queda `serverUrl=…&token=…`.
- 20 y 21 may, `gvm.mt.totem` 4985ca6 y 0281513 ("deeplink"): el kiosco deja de navegar dentro de la SPA y hace `window.location.href = /{slug}/waiting-room?serverUrl=…&token=…` (o `/triage-manual?…`, `/cmd/sessionResume?sessionId=…&serverUrl=…&token=…`). Una navegación "dura" sólo sirve si una app que **muestra el kiosco adentro** mira cada URL que se carga.
- Conclusión: la app del proveedor alojaba el kiosco en un WebView, tomaba `serverUrl` y `token` de la URL, entraba a la sala `encounter-{id}` con ese token (identidad del paciente, permiso de publicar) y publicaba el otoscopio y el estetoscopio con `start_oth`/`start_steth`. No tenemos su APK: es una deducción del historial.

Ventaja extra: con el kiosco y el estetoscopio en la misma app (mismo UID), Android no silencia la captura del estetoscopio mientras Zoom tiene el micrófono (la regla de `AudioPolicyService` es por UID: el dueño del modo de comunicación sí puede capturar). Ya no hace falta que el kiosco suelte el micrófono.

## Qué cambia en Berry Med (sobre el PR #19, rama `feat/livekit-teleconsulta`)

### Pantalla del kiosco

- `KioskActivity` nueva: pantalla completa, horizontal, sin barras (modo inmersivo), con un `WebView` que carga la URL del kiosco. Campo nuevo de configuración **"URL del kiosco"** (la misma que hoy abre Chrome, con el slug de la organización); sin URL, la pantalla lo dice y ofrece abrir la configuración.
- WebView: JavaScript, DOM storage, `mediaPlaybackRequiresUserGesture = false`, cookies (incluidas third-party si Zoom las pide), user agent de Chrome móvil sin el marcador `wv` si Zoom rechaza WebView. `WebChromeClient.onPermissionRequest` concede `RESOURCE_VIDEO_CAPTURE` y `RESOURCE_AUDIO_CAPTURE` sólo al origen de la URL del kiosco y a los de Zoom que la llamada necesite; los permisos de Android (cámara, micrófono) se piden antes.
- Atrás no sale del kiosco (el paciente no tiene que poder salir). Se sale a la configuración de Berry Med con un gesto oculto (por ejemplo, cinco toques en una esquina) y el PIN del Panel Local.
- `KioskActivity` declara `HOME` y `DEFAULT` en su intent-filter: el instalador elige Berry Med como app de inicio y Android la abre al encender. Con eso, al estar adelante, tiene cámara y micrófono sin trucos: se quitan el permiso `SYSTEM_ALERT_WINDOW` y el arranque de `MainActivity` desde `BootReceiver` del PR #19. El resto del servicio en primer plano (`promote`, tipos que nunca bajan) se mantiene.
- El Panel Local y Testing siguen como están; al abrirlos se corta la teleconsulta (ya lo hace el PR). Al volver, el kiosco sigue donde estaba.

### El token sale de la URL

- En el `WebViewClient`, en cada carga (`onPageStarted` y `doUpdateVisitedHistory`) y en `shouldOverrideUrlLoading`: si la URL trae `serverUrl` y `token`, se toman. También se acepta `koplak://auth?serverUrl=…&token=…` (se intercepta y no se navega), por si alguna versión vieja de la API lo manda.
- `serverUrl` es el subdominio de LiveKit Cloud (`services.livekit.subdomain`): si no empieza con `ws://`, `wss://`, `http://` o `https://`, el servidor es `wss://{serverUrl}.livekit.cloud`. Si ya es una URL, se usa tal cual. Test con los dos casos.
- El token vence a los 15 minutos para **entrar**; los instrumentos se encienden más tarde (triage, sala de espera). Por eso Berry Med entra a la sala apenas ve el token, sin publicar nada, y se queda: LiveKit renueva el token mientras está conectado. Con `start_oth`/`start_steth` publica, con `stop_*` despublica y sigue en la sala.
- Se sale de la sala cuando llega un token nuevo (otro paciente: se sale y se entra con el nuevo) o cuando el kiosco vuelve a una URL sin encuentro (la pantalla de inicio o de login; definir la regla mirando las rutas de `gvm.mt.totem`). Tope de seguridad: 3 horas en la sala.
- `stop-monitoring` **no** saca de la sala, porque también llega al completar los signos vitales, antes de la videollamada: sólo despublica lo que esté publicando.
- Si la conexión se cae y LiveKit no la recupera, queda el error en Testing y en el log; se reintenta una vez con el mismo token sólo si no pasaron 15 minutos desde que se vio.
- Las órdenes `start_*` que llegan sin sala (no se vio token) se registran y no hacen nada.
- Se quitan del PR #19: el cliente del token de la API (`TeleToken`, `livekitTokenUrl`, sus tests y el manejo de 404/409/422/429). `TeleStreamer` pasa a recibir las credenciales del kiosco en lugar de pedirlas.
- Nunca se escribe el token en el log ni en Testing (sólo "token visto a las HH:MM, vence a las HH:MM", sacado del JWT sin verificar la firma).

### Audio

- Se mantiene todo lo del PR #19 (sin procesamiento de voz, `NoAudioHandler`, fuente `UNPROCESSED`/`MIC`, adaptador como dispositivo preferido, filtro Amplio, sin DTX). Ojo: ahora Zoom corre en el mismo proceso y el WebView pone el `AudioManager` en modo comunicación: Berry Med sigue sin tocarlo, y el módulo de audio de LiveKit tiene que convivir con el de Chromium. Verificar en el emulador que con una llamada WebRTC en el WebView la captura del estetoscopio **no** queda silenciada (`dumpsys audio`) y que el audio de la llamada no se corta al publicar.

## Verificación en el emulador

- Una página de prueba local (servida desde la Mac, el emulador la ve en `10.0.2.2`) que imita el kiosco: pide `getUserMedia` con cámara y micrófono (una llamada WebRTC en loopback alcanza), y un botón que hace `window.location.href = '/waiting-room?serverUrl=ws://10.0.2.2:7880&token=…'` con un token de `lk token create`. Con la URL del kiosco apuntando a esa página:
  - Berry Med entra a la sala al ver la URL (`lk room participants list`).
  - Con la orden de Testing (o una orden simulada en los tests) publica el estetoscopio con el micrófono virtual y el otoscopio con la cámara virtual **mientras la página tiene la cámara y el micrófono abiertos**, y la captura del estetoscopio no está silenciada.
- Cargar el kiosco real de desarrollo en el WebView (la URL que usa hoy Chrome en el entorno Dev) y confirmar que abre, que el login funciona y que no hay errores de Zoom en la consola del WebView (`chrome://inspect` o `adb logcat`). Si se puede llegar a una videollamada real, mejor; si no, decirlo.
- Reiniciar el emulador con Berry Med como app de inicio y confirmar que abre el kiosco y que el servicio queda con cámara y micrófono.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.

## Documentación

- README: el kiosco ahora corre dentro de Berry Med (no en Chrome); cómo configurar la URL del kiosco y elegir Berry Med como app de inicio; cómo salir a la configuración; qué mirar en Testing. Sacar lo de "Mostrar sobre otras apps".
- `docs/decisiones.md`: entrada con esta revisión (sin cambios en la API ni en el kiosco; se cierran gvm.mt.api#29 y gvm.mt.totem#70; el kiosco va dentro de Berry Med como hacía el proveedor).
- `docs/hardware.md`: el límite del micrófono compartido queda resuelto por compartir la app.
- El plan 6 original queda como historia; commitear esta revisión.

## Riesgos para la tablet

- Que Zoom Video SDK no ande en el WebView (cookies, `SharedArrayBuffer`, user agent). El proveedor lo hacía, pero no sabemos con qué ajustes.
- Que el WebView del sistema esté desactualizado en la tablet.
- Que la regla de "salir de la sala" deje a Berry Med en una sala vieja si el kiosco no vuelve al inicio.
