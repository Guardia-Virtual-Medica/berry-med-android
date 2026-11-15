# Plan 16: el panel lateral de estado, en el kiosco y en el Panel Local

Plan para un agente (14 nov 2026), sobre `main` en `3facdc3` (1.3.8). Los números de línea son de ese commit. El diseño final está en `docs/redisenio/design_handoff_panel_lateral/`: README, `Panel Lateral.html` y `screenshots/`. **Las medidas, los colores y los textos salen de ese README; este plan dice dónde va cada cosa y de dónde sale cada dato.**

Regla que ordena todo: **el panel muestra, no decide.** Lee el estado que ya calcula la app y usa los caminos que ya existen: el PIN del Panel Local, `startLocal`/`stopLocal`, el ajuste `callAudioOutput`. No cambia cómo se conectan los dispositivos.

## Qué es

Una columna nativa de 296 dp a la derecha con cuatro secciones:

- Wi-Fi en la cabecera;
- Modo, con el botón para cambiar;
- Dispositivos;
- Sonido.

Se cierra a un riel de 64 dp. Va en `KioskActivity` (al lado del WebView) y en `LocalPanelActivity` (al lado del Panel Local). Al arrancar el proceso aparece abierto.

## 1. Una vista compartida y un modelo sin Android

**Qué pasa hoy.** La app es Views/XML con ViewBinding, sin Compose. No hay nada parecido a este panel.

- `KioskActivity` arma su raíz en código: un `FrameLayout` con el WebView, `empty`, `offline` y la esquina de los cinco toques (`ui/KioskActivity.kt:110-117`, `:243`).
- `LocalPanelActivity` infla `activity_local_panel.xml`. La raíz es un `FrameLayout` con `@+id/panel` y dos capas a pantalla completa: `finishLayer` (`:1487`) y `lock` (`:1545`).

**Cambios.**

- `ui/status/StatusPanelUi.kt`, **sin Android**: la data class `StatusPanelState` con lo que dibuja cada sección y las funciones puras que la arman desde los datos de los puntos 2 a 5. Va en el mismo estilo que `ui/panel/PanelUi.kt`: usá su `Link`/`Tone` si alcanzan, o un `Tone` propio con `OK`, `WARN` y `BAD`.
- `ui/status/StatusPanelView.kt`: un `FrameLayout` que infla `res/layout/view_status_panel.xml` (abierto) y `res/layout/view_status_rail.xml` (riel), y alterna entre los dos.
  - `fun render(state: StatusPanelState)`.
  - Callbacks: `onSwitchMode`, `onAudioChoice(String)`, `onOpenWifi`, `onToggle(open: Boolean)`.
- Estilos en `res/values/themes.xml` (familia `Status.*`), fondos en `res/drawable/status_*.xml` e íconos `ic_*.xml` de Material Symbols Rounded (lista en el handoff). Colores, sólo los de `colors.xml`.
- **Abierto o cerrado:** vive en memoria en `TotemRuntime` (`var statusPanelOpen = true`), sin guardar en DataStore. Así se mantiene al pasar del kiosco al Panel Local y vuelve a abierto al reiniciar el proceso.
- **Refresco:** cada actividad llama a `render` desde su loop de 500 ms. `LocalPanelActivity` ya tiene uno (`RENDER_MS`, `:1000`). En el kiosco, un `Handler` mientras esté en `onStart`/`onStop`, igual que `MainActivity` (`:67-76`).

**Tests.** `StatusPanelUiTest` con las reglas de los puntos 3, 4 y 5.

## 2. Una lectura de estado sin efectos

**Qué pasa hoy.** `localSnapshot()` (`service/TotemRuntime.kt:1095`) sirve para casi todo y no tiene efectos. `testingSnapshot()` sí los tiene (`testingSession.poll`): no lo uses. Hoy nada público dice:

- si está el YKUSH3 (`hub.ykushPlugged()`, `usb/UsbHub.kt:102`);
- si falta el permiso USB (`hub.permissionMissing()`, `:68`);
- si hay una teleconsulta en una sala (`tele.status.room`, `tele/TeleStreamer.kt:15`).

**Cambios.**

- `runtime.statusSnapshot(): StatusSnapshot` (en `service/`), barata y sin efectos, con:
  - el `LocalSnapshot` de siempre;
  - `ykushPlugged` y `usbPermissionMissing`;
  - `localMode` y `sessionActive`;
  - `teleInRoom`: verdadero si `tele.status.room` no es `DISCONNECTED`;
  - `callAudioChoice` (`config.callAudioOutput`);
  - `jackPresent` y `callRoute` (punto 5).
- `runtime.modeSwitchBlock(): String?`: devuelve el texto «Hay una consulta en curso. Terminala para cambiar.» cuando `sessionActive || teleInRoom`, y `null` si se puede cambiar. Sacá la condición a una función pura con test.

## 3. Dispositivos: qué estado lleva cada uno

Va en una función pura en `StatusPanelUi`, con test por fila. Entra el `StatusSnapshot` y salen siete `(nombre, tono, texto)`. Usá las funciones que ya existen en `PanelUi`: `berryLive` (`:333`) y la nota del corazón (`heartNote`, `:370`).

| Fila | Verde | Ámbar | Rojo |
|---|---|---|---|
| SpO2, Presión | `berryLive` → «Conectado» | `berryUp` sin datos frescos → «Conectando» | si no → «Desconectado» |
| ECG | igual | lo mismo, más «Electrodos sueltos» cuando `heartNote` lo dice | igual |
| Temperatura | `thermoConnected` → «Conectado» | — | «Desconectado» |
| Otoscopio | `otoscopeState == ON` → «En vivo»; enchufado → «Conectado»; en Local con el YKUSH3 y el puerto apagado → «Listo» | `POWERING_PORT` / `WAITING_CAMERA` → «Conectando» | sin YKUSH3 y sin otoscopio → «Sin hub»; con YKUSH3 en Remoto y sin otoscopio → «Desconectado» |
| Estetoscopio | `stethoRunning` → «En vivo»; enchufado → «Conectado»; en Local con YKUSH3 → «Listo» | `stethoStarting` → «Conectando» | igual que el otoscopio |
| Hub USB | `ykushPlugged` y con permiso → «Conectado» | — | sin YKUSH3 → «Desconectado»; `usbPermissionMissing` → «Sin permiso USB» |

- **Por qué «Listo».** En Local los puertos del otoscopio y del estetoscopio arrancan apagados (`HubRestState.wanted`, `service/HubRestState.kt`) y se encienden al abrirlos. Eso no es un error y no puede salir en rojo.
- **El conteo** cuenta los verdes. Va verde con 7/7, rojo si hay algún rojo y ámbar si sólo hay ámbar.

## 4. Wi-Fi

**Qué pasa hoy.**

- El único código de red es el `NetworkCallback` del kiosco (`KioskActivity.kt:96-104`), que sólo sirve para reintentar.
- El manifiesto no tiene `ACCESS_WIFI_STATE` ni `ACCESS_FINE_LOCATION`. Sin ubicación, Android 10 o más devuelve `<unknown ssid>`.
- `targetSdk` es 35 y `minSdk` es 26.

**Cambios.**

- `net/WifiWatch.kt`: un `registerDefaultNetworkCallback` propio que expone un `WifiState`:
  - `connected`, `validated`, `ssid?`, `level` de 0 a 4.
  - En API 31 o más, con `FLAG_INCLUDE_LOCATION_INFO`: el SSID sale de `(capabilities.transportInfo as? WifiInfo)?.ssid`, sin comillas, y `null` si es `<unknown ssid>`.
  - El nivel sale de `capabilities.signalStrength`. En API 26 a 28 usá `WifiManager.connectionInfo`.
  - Vive en el runtime, así lo leen las dos actividades.
- Una función pura para las barras: 4 desde −60 dBm, 3 hasta −70, 2 hasta −80 y 1 por debajo. Y otra para el estado:
  - «Señal buena»;
  - «Señal débil» con 1 barra;
  - «Sin internet» si no está validada;
  - «Sin Wi-Fi» si no hay transporte Wi-Fi.
  - Las dos con test.
- Manifiesto: `ACCESS_WIFI_STATE` y `ACCESS_FINE_LOCATION`. Pedí la ubicación junto con cámara y micrófono en `KioskActivity.askPermissions` (`:351-354`). Si la niegan, el nombre de la red dice «Wi-Fi» y nada más.
- Sin red, la cabecera abre `Settings.Panel.ACTION_WIFI` en API 29 o más, y `Settings.ACTION_WIFI_SETTINGS` antes.

## 5. Sonido

**Qué pasa hoy.**

- El micrófono de la llamada sigue al dispositivo de comunicación (`audio/CallAudioRouter.kt`, KDoc `:24-25` y `:44-49`).
- El único control es `BerryConfig.callAudioOutput`: `"auricular"` (por defecto) o `"parlante"` (`config/BerryConfig.kt:50`). Se elige en la configuración, y `AudioPick.callOutput` (`audio/AudioPick.kt:91`) lo aplica.
- No se puede usar el micrófono de la tablet con un auricular en el jack sin pasar también el sonido al parlante.

**Cambios.**

- El segmentado escribe `callAudioOutput`: Auricular es `"auricular"` y Altavoz (al principio «Tablet») es `"parlante"`.
  - Guardá con `ConfigStore.save(config.copy(callAudioOutput = …))` fuera del hilo principal. El runtime ya reacciona al cambio y llama a `callAudio.refresh()` (`TotemRuntime.kt:305-309`).
  - **No pide PIN:** es el mismo ajuste de la configuración, y no toca nada clínico.
- `jackPresent`: algún dispositivo de salida con `AudioPick.isJack(type)` (`:44`). Sin jack, el segmento Auricular queda deshabilitado y se ve Altavoz elegido, aunque el ajuste guardado diga `"auricular"`. No reescribas el ajuste: cuando vuelva el auricular, vuelve solo.
- `callRoute` (lo que se muestra): `TABLET` si el ajuste es `"parlante"` o no hay jack, y `HEADSET` si no. Función pura con test.
- Se llama «Sonido» y se ve igual en los dos modos. La opción se llama «Altavoz», no «Tablet». Altavoz mueve la llamada; el estetoscopio sigue sólo en el auricular (cambio del 15 de noviembre de 2026, ver `docs/decisiones.md`).

## 6. El kiosco con el panel

**Cambios en `KioskActivity`.**

- La raíz pasa a ser un `LinearLayout` horizontal:
  - a la izquierda, el `FrameLayout` de hoy con `weight = 1` (WebView, `empty`, `offline` y la esquina de los cinco toques);
  - a la derecha, el `StatusPanelView` con su ancho, 296 o 64 dp.
  - `offline` y `empty` tapan sólo el lado izquierdo.
- La esquina de los cinco toques y su PIN hacia la configuración (`:413-467`) **no cambian**.
- Cerrar o abrir el panel cambia el ancho del WebView y la página se acomoda sola. No recargues.

## 7. El Panel Local con el panel

**Cambios en `activity_local_panel.xml` y `LocalPanelActivity`.**

- Raíz horizontal:
  - a la izquierda, un `FrameLayout` con `weight = 1` que contiene `@+id/panel`, `finishLayer` y `lock`, para que las capas tapen sólo el Panel Local;
  - a la derecha, el `StatusPanelView`.
- **Sacá los seis chips de la cabecera** (`chipEcg` … `chipStetho`, `:62-96`) junto con `chip()` (`LocalPanelActivity.kt:826`) y su llamada. Si `PanelUi.chips`/`Chips` (`PanelUi.kt:28`, `:336`) quedan sin uso, borralos con sus tests.
- **La toolbar del ECG en dos filas** cuando el panel está abierto. Arriba van ECG, la señal y Congelar; abajo, los tres segmentados. Con el riel, una fila como hoy. Decidilo por `statusPanelOpen`, no midiendo anchos. Medidas en el handoff, «Layout».
- La columna de vitales sigue en 312 dp.

## 8. Cambiar de modo

**Qué pasa hoy.**

- Del kiosco al Panel Local: cinco toques, PIN, `MainActivity`, el botón del Panel Local y **otro PIN** en la pantalla `lock`.
- Del Panel Local al kiosco: Finalizar o el «volver» de la pantalla de PIN, que hacen `finish()` y dejan a la vista lo que había atrás. Las actividades son `singleTask`, cada una con su afinidad (`AndroidManifest.xml`).

**Cambios.**

- **Remoto → Local.**
  - Si `modeSwitchBlock()` no es null, el botón va bloqueado (punteado, con candado y el texto).
  - Si se puede cambiar: diálogo «¿Pasar al Panel Local?» y, con «Cambiar», `startActivity(Intent(this, LocalPanelActivity::class.java))`.
  - El Panel Local ya bloquea en `onStart` (`:270-276`) y pide **su** PIN: es el único PIN de este camino. `startLocal` sigue haciendo su control de «Hay una consulta enviando…».
- **Local → Remoto.**
  - Desbloqueado: diálogo «¿Volver a ONDOCTOR365?». Con «Volver», el mismo camino de Finalizar (`stopLocal` incluido, `:285-302`) y después `startActivity(Intent(this, KioskActivity::class.java))`.
  - Bloqueado (pantalla de PIN): sin diálogo, abre el kiosco y hace `finish()`.
- Los dos diálogos usan el estilo del de Finalizar, en un layout compartido `res/layout/dialog_mode_switch.xml`. El kiosco también lo usa, aunque arme el resto de su vista en código.

## Lo que no entra

- Abrir el panel solo cuando algo pasa a rojo (decidido que no).
- Usar el micrófono de la tablet con el sonido en el auricular: hoy Android no lo permite con este ruteo. Si hace falta, va en otro plan.
- `MainActivity`, Testing y sus chips: no cambian.
- La versión: **no la cambies**. La sube la sesión principal en el PR de release (1.3.9, `versionCode` 18).

## Tests y entrega

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.
- Si el emulador `berry_tablet` está prendido (`adb devices`), instalá el APK y verificá a mano:
  - el panel abierto al arrancar, cerrar y abrir, y que el estado se mantenga entre el kiosco y el Panel Local;
  - Remoto → Local con un solo PIN, y Local → Remoto por los dos caminos;
  - el botón bloqueado con una consulta;
  - el ECG en dos filas y en una;
  - el segmentado de sonido guardando el ajuste.
  - Si no está prendido, no lo levantes: decí qué quedó sin verificar en pantalla.
  - El emulador no tiene plaqueta, hub, termómetro ni jack: los estados de los dispositivos y del sonido se confirman en la tablet. Decilo.
- Capturas del emulador en `docs/capturas/plan-16/`, comparadas con `docs/redisenio/design_handoff_panel_lateral/screenshots/`.
- Documentación, en el mismo PR:
  - `README.md`: una sección «Panel lateral» (qué muestra, cómo se cambia de modo, de dónde sale cada estado y el permiso de ubicación), y actualizá «Kiosco» y «Panel Local» (el PIN único, los chips que ya no están).
  - `docs/decisiones.md`: una entrada con fecha 14 de noviembre de 2026 con las decisiones de este plan:
    - el rojo para desconectado;
    - «Listo» en Local;
    - Altavoz = `"parlante"`;
    - no se abre solo;
    - la ubicación para el SSID.
- Rama `feat/panel-lateral` desde `main`; PR contra `main`. Commits en español con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commiteá este plan y la carpeta del handoff.
- En el informe final: qué cambió por punto, qué decidiste donde el plan dejaba elegir y qué quedó sin verificar.
