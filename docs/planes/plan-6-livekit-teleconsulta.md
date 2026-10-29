# Plan 6: otoscopio y estetoscopio en la teleconsulta por LiveKit

Plan escrito para que lo ejecuten tres agentes, uno por repositorio (29 oct 2026). Reemplaza la decisión "LiveKit queda como está" de `docs/decisiones.md`: el usuario pidió que durante la teleconsulta de OnDoctor el médico vea el otoscopio y escuche el estetoscopio del tótem.

## Cómo está hoy (rastreado en los tres repos)

- **API (`gvm.mt.api`)**. `IssueEncounterTotemToken` emite un token de LiveKit con permiso de publicar (sala `encounter-{encounter.external_id}`, identidad = paciente, 15 min) y `CreateEncounterController` lo pega en `redirect_url` como `serverUrl=…&token=…`. Nadie lo usa. Al médico, `StartVideoConsultationController` le da un token **sólo de suscripción** (`livekit_token`, `livekit_url`, `livekit_host`) si `Encounter::streamsMedicalInstruments()` (encuentro de tótem sin turno, con otoscopio o estetoscopio habilitado en el tótem).
- **Órdenes**. Los botones Encender/Apagar del kiosco (`gvm.mt.totem`, `src/components/Berry/Berry.tsx`) llaman `current-encounter/otoscope/start|stop` y `stethoscope/start|stop`. La API emite `start_oth`, `stop_oth`, `start_steth`, `stop_steth` (`AbstractEncounterTotemBroadcast`) en `private-encounter.{id}` y en el canal público `totem.{totem.external_id}`, con `{encounter_external_id, totem_id}`. Al terminar la videoconsulta o el encuentro, `StopTotemStreams` emite `stop-monitoring`, `stop_steth` y `stop_oth`. El kiosco sólo deja uno encendido a la vez.
- **Berry Med** ya está suscrito a `totem.{id}` (`SessionPusher`, `config.publicChannel`) y escucha `start-monitoring`, `stop-monitoring` y `start-blood-pressure`. No escucha los eventos de los instrumentos ni tiene cliente de LiveKit.
- **Médico (`gvm.mt.doctors`, `useLiveKitDevices.ts`)**. Se conecta a la sala con su token y toma **cualquier video remoto como otoscopio y cualquier audio remoto como estetoscopio**. No hace falta tocarlo.
- **La videollamada del kiosco es Zoom Video SDK** (`@zoom/videosdk` 2.3.15, `src/components/Videocall/ZoomMeeting.tsx`), no LiveKit. LiveKit sólo lleva los instrumentos.

## Dos límites de Android que definen el diseño

1. **Micrófono compartido.** Con Chrome en la llamada de Zoom, el audio queda en `MODE_IN_COMMUNICATION` y Chrome captura con `VOICE_COMMUNICATION`. En `AudioPolicyService::updateUidStates_l` (AOSP, rama main) una app que no es dueña del modo de comunicación no puede capturar mientras el dueño está capturando (`canCaptureIfInCallOrCommunication`), y además pierde contra cualquier captura "privacy sensitive" activa. Resultado: **Berry Med recibe silencio del adaptador del estetoscopio mientras Zoom tenga el micrófono abierto**, aunque sean dispositivos distintos. La salida: mientras el estetoscopio está encendido, el kiosco suelta el micrófono de Zoom (deja el parlante). Cuando el dueño del modo no captura, Berry Med sí puede.
2. **Cámara y micrófono desde segundo plano.** Berry Med corre detrás de Chrome. Un servicio en primer plano sólo accede a cámara y micrófono si se puso en primer plano con los tipos `camera|microphone` **mientras la app tenía una pantalla visible** (Android 11+; en Android 14+ tira `SecurityException` si no). `BOOT_COMPLETED` no da esa excepción. Hoy `MonitorService` usa sólo `connectedDevice` y arranca también desde el arranque de la tablet.

## Diseño

### 6a. API: token para publicar los instrumentos (`gvm.mt.api`)

Ruta nueva, junto al webhook de métricas en `routes/api/webhooks.php`:

```
POST /api/v1/webhooks/totem/{medicalTotemExternalId}/livekit-token
body: { "encounter_external_id": "<uuid>" }
```

- Sin autenticación, con el mismo modelo de confianza que `metrics` (el UUID del tótem). `throttle:30,1`.
- 404 si el tótem no existe o está deshabilitado. 409 si el encuentro no es de ese tótem, no está en `PendingVitalSigns` ni `StartVideoConsultation` (los mismos estados que `StartCurrentEncounterOtoscopeController`), o `streamsMedicalInstruments()` es falso. 422 si falta el UUID.
- 200 con `{ "server_url": config('services.livekit.url'), "room": "encounter-{external_id}", "token": "<jwt>" }`.
- Acción nueva `IssueEncounterInstrumentsToken` (mismo estilo que `IssueEncounterTotemToken`): identidad `instruments-{totem.external_id}` (distinta del paciente, si no LiveKit echa a uno de los dos), nombre "Instrumentos del tótem", TTL 15 min (sólo hace falta para entrar; LiveKit renueva el token mientras está conectado). Grant: `roomJoin`, esa sala, `canPublish=true`, `canSubscribe=false`, `canPublishData=false` y, si el SDK `agence104/livekit-server-sdk` lo permite, `canPublishSources=['camera','microphone']`.
- No tocar `redirect_url` ni el token del médico.
- Tests Pest (Feature) con los casos 200, 404, 409 (otro tótem, estado inválido, sin instrumentos), 422, y que el JWT decodificado tenga identidad, sala y permisos esperados. Pint y PHPStan en verde.
- Documentar en el PR que es un webhook sin autenticación como `metrics`, que quien conoce el UUID del tótem y el del encuentro activo puede publicar video en esa sala, y que la mejora es un secreto por tótem para los dos webhooks (no se hace acá).

### 6b. Kiosco: soltar el micrófono mientras suena el estetoscopio (`gvm.mt.totem`)

- Cuando el estetoscopio se enciende con éxito (`startStethoscopeMutation.onSuccess`), Zoom deja de capturar el micrófono pero sigue reproduciendo: `stream.stopAudio()` y después `stream.startAudio({ speakerOnly: true })` (verificar en los tipos de `@zoom/videosdk` 2.3.15 la forma exacta; `muteAudio()` **no** alcanza si mantiene el micrófono abierto). Al apagarlo, al encender el otoscopio (que apaga el estetoscopio) y al desmontar la videollamada, se vuelve al audio completo (`stopAudio()` + `startAudio()`), y si se estaba muteado se respeta.
- Ahora `Berry.tsx` y `ZoomMeeting.tsx` no comparten el stream. Exponer desde la videollamada una función chica (store o contexto ya existente; mirar `useSocketStore`) para pedir "micrófono en pausa" / "micrófono normal"; no duplicar el cliente de Zoom.
- Bajo la tarjeta del estetoscopio, mientras está encendido, un texto corto: "El médico escucha el estetoscopio. Tu micrófono está en pausa."
- Si llega `stop_steth` por Pusher (fin de la consulta), el kiosco ya limpia el estado; que también restaure el micrófono si quedó en pausa.
- `npm run lint` y build en verde. No hay tests de UI; describir en el PR cómo se probó (con Chrome y `chrome://webrtc-internals` o el indicador de micrófono de Android: con el estetoscopio encendido Chrome no debe tener captura de audio abierta).

### 6c. Berry Med publica a LiveKit (`berry-med-android`)

**Órdenes.** `SessionPusher` escucha además `start_oth`, `stop_oth`, `start_steth`, `stop_steth` en el mismo canal y le pasa al listener el `encounter_external_id` del cuerpo del evento (JSON; si falta, se ignora la orden y se registra). No dependen de que haya una consulta de signos vitales enviando.

**`TeleStreamer`** (paquete nuevo `tele/`), dueño de la sala:

- `start(instrument, encounterId)`: si el Panel Local o Testing tienen el instrumento abierto, no hace nada y lo registra (son exclusivos con Chrome). Pide el token a la ruta de 6a (`apiBaseUrl` + `/webhooks/totem/{totemId}/livekit-token`, con el mismo cliente HTTP y tiempos que `MetricsPoster`), se conecta (`Room.connect(server_url, token)`) si no lo está, y publica la pista del instrumento. Como el kiosco enciende uno solo, al empezar uno se despublica el otro.
- `stop(instrument)`: despublica y suelta el instrumento. Sin pistas, desconecta. `stop-monitoring` y un `start` de otro encuentro también cierran todo.
- Puertos del YKUSH3: encender el del instrumento (y el del auricular no) con la regla de `PanelPorts` (sólo apaga al final lo que encendió él; lectura fallida, no apaga).
- Todo en un hilo propio; errores al log `[TELE]` y al estado, nunca tiran la app.

**Video del otoscopio.** Una pista de video con un capturador propio (`VideoCapturer` del SDK de LiveKit) alimentado por cuadros, sin vista:
- Camino UVC (`UvcOtoscope`/AUSBC): callback de cuadros NV21 → `NV21Buffer` → `VideoFrame`. Preferido en la teleconsulta porque libuvc va por USB y no pasa por el servicio de cámara (no compite con la cámara de la tablet que usa Zoom).
- Camino Camera2 (CameraX con `ImageAnalysis` YUV_420_888 → I420), con un `LifecycleOwner` propio.
- Elegir el camino con la misma lógica que el Panel Local (`OtoscopeProbe`/`OtoscopePower`). Separar en `OtoscopeSource` lo que es "abrir en una vista" de "entregar cuadros", sin romper el Panel Local.
- Publicar a 800×800 (o lo que entregue el otoscopio), 15 a 30 fps, sin simulcast, `degradationPreference` que mantenga la resolución, unos 1,5 Mbps.

**Audio del estetoscopio.** Pista de audio del adaptador USB (`stethoInputName`, nunca el micrófono de la tablet ni el del auricular), sin ningún procesamiento de voz:
- `LiveKitOverrides` con un `audioHandler` que **no toque** `AudioManager` (ni modo, ni foco, ni ruteo): si Berry Med pone `MODE_IN_COMMUNICATION` le rompe el audio a Zoom.
- Módulo de audio de WebRTC con fuente `UNPROCESSED` (o `MIC` si la tablet no la tiene), dispositivo preferido = el adaptador, AEC y NS por hardware apagados; opciones de la pista con eco, ruido, ganancia automática y pasa-altos apagados.
- El filtro Amplio (20 a 1000 Hz, `StethoFilter`) y la ganancia del volumen por defecto aplicados a la captura si el SDK permite procesar el buffer (procesador de captura o callback de buffer); si no, sin filtro, y decirlo en el PR.
- Opus sin DTX (si no corta los latidos como silencio), preset de música, 64 kbps o más.
- Registrar `AudioRecordingConfiguration.isClientSilenced()` del lado de Berry Med y mostrarlo: es la prueba de que el kiosco soltó el micrófono.
- La sala no se suscribe a nada y Berry Med no reproduce nada.

**Servicio en primer plano.**
- Manifest: `foregroundServiceType="connectedDevice|camera|microphone"` en `MonitorService`, permisos `FOREGROUND_SERVICE_CAMERA` y `FOREGROUND_SERVICE_MICROPHONE`.
- `MonitorService` sigue arrancando con `connectedDevice` desde el arranque de la tablet. Cuando hay una pantalla de Berry Med visible (`MainActivity`, `TestingActivity` o `LocalPanelActivity` en `onResume`) y los permisos de cámara y micrófono están otorgados, se lo pasa a `connectedDevice|camera|microphone`. A partir de ahí, cada `startForeground` repite los mismos tipos (nunca menos) y nunca se piden más tipos desde segundo plano.
- Si la tablet arrancó y nadie abrió Berry Med, el streamer no puede capturar: lo dice en la notificación ("Abrí Berry Med una vez para habilitar otoscopio y estetoscopio en la teleconsulta") y en el log al recibir la orden.
- Para el tótem desatendido: si la app tiene el permiso "Mostrar sobre otras apps" (`SYSTEM_ALERT_WINDOW`, se otorga a mano en Ajustes al instalar), `BootReceiver` abre `MainActivity`, que hace el paso de arriba y se manda atrás (`moveTaskToBack(true)`). Verificar en el emulador con Android 15 que eso funciona después de un reinicio; si Android no lo deja, dejar sólo el aviso y decirlo en el PR.

**Testing → "Teleconsulta (LiveKit)".** Una sección nueva que muestra: si el servicio tiene cámara y micrófono, el estado de la sala (desconectada, conectando, conectada, error), qué pista publica, cuadros por segundo enviados, nivel del audio y si Android lo silencia. Y una prueba manual con dos campos, "Servidor" y "Token", y botones "Publicar otoscopio" / "Publicar estetoscopio" / "Cortar", que usan el mismo `TeleStreamer` sin pedir token a la API (para probar con un servidor LiveKit propio y un token hecho con `lk token create`).

**SDK.** `io.livekit:livekit-android` 2.x, la última estable con al menos dos semanas. Verificar en el código fuente del SDK de esa versión los nombres exactos (capturador propio, `LiveKitOverrides`/`AudioOptions`, procesador de captura, opciones de publicación) antes de escribir; si alguno no existe, la alternativa más chica y decirlo en el PR. Ojo con el tamaño del APK y con choques de librerías nativas con AUSBC (`packaging { jniLibs { pickFirsts } }` si hace falta) y con el ABI (el SDK trae x86_64, así que el emulador sirve para todo menos el camino UVC).

**Tests JUnit.** Parseo del cuerpo de los eventos de instrumentos; la máquina de estados del streamer con fakes (start de un instrumento despublica el otro, stop del último desconecta, `stop-monitoring` cierra todo, orden con el Panel Local abierto se ignora, error de token deja estado de error y no reintenta en loop); la regla de los tipos del servicio (nunca bajar, nunca subir desde segundo plano); el armado de la URL del token.

**Verificación en el emulador** (`berry_tablet`, `emulator-5554`; si no está corriendo, arrancarlo con `~/Library/Android/sdk/emulator/emulator -avd berry_tablet`):
- Un servidor LiveKit local (`brew install livekit livekit-cli`; `livekit-server --dev --bind 0.0.0.0`, credenciales `devkey`/`secret`, el emulador lo ve en `ws://10.0.2.2:7880`). Token con `lk token create --join --room prueba --identity instruments --valid-for 1h`.
- En Testing: publicar el estetoscopio con el micrófono virtual del emulador haciendo de adaptador (sólo para la prueba, sin commitear el cambio de nombre) y el otoscopio con la cámara virtual del emulador por el camino Camera2; confirmar con `lk room participants list` (o un suscriptor `lk`) que llegan las pistas, con la app **en segundo plano** (Chrome adelante).
- Confirmar con `adb shell dumpsys activity services com.guardiavirtual.berrymed` los tipos del servicio y con `adb shell dumpsys media.audio_policy` que la captura no está silenciada; y repetir con Chrome capturando el micrófono (una página de prueba de `getUserMedia`) para ver que ahí **sí** queda silenciada y que Testing lo muestra.
- Reiniciar el emulador y verificar el camino de `BootReceiver` con y sin "Mostrar sobre otras apps".
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde. Capturas de Testing en `docs/capturas/` (no se commitean).

**Documentación.** README: sección nueva "Teleconsulta: otoscopio y estetoscopio" (qué pasa al tocar Encender en el kiosco, que el tótem tiene que tener los instrumentos habilitados en el panel de administración, el paso de abrir Berry Med después de reiniciar o el permiso "Mostrar sobre otras apps", y qué mirar en Testing y en `adb logcat -s BerryMed`). `docs/decisiones.md`: entrada del 29 de octubre de 2026 con este plan. `docs/hardware.md`: el límite del micrófono compartido. Commitear también este plan.

## Qué no hace

- No cambia la app del médico. Nota para el usuario: `useLiveKitDevices.ts` espeja el video (`scaleX(-1)`), lo que invierte la imagen del otoscopio; es una línea en `gvm.mt.doctors` si se decide sacarlo.
- No graba nada ni manda el audio o el video a otro lado que no sea la sala.
- No agrega autenticación nueva a los webhooks del tótem.
- No toca el Panel Local salvo la separación de `OtoscopeSource` para entregar cuadros.

## Riesgos para la prueba en la tablet

- Que el otoscopio por Camera2 no se pueda abrir mientras Zoom usa la cámara de la tablet (límite de cámaras abiertas a la vez). Por eso se prefiere UVC.
- Que el adaptador de audio no ofrezca `UNPROCESSED` y Android le aplique algún procesamiento.
- Que el fabricante de la tablet tenga reglas más duras para cámara o micrófono desde segundo plano.
- La latencia del audio del estetoscopio para el médico (LiveKit + Opus: unos 200 a 400 ms), que no es un problema para auscultar.
