# Plan 18: que la teleconsulta funcione, con menos piezas

Plan para un agente (15 nov 2026). Sale de una consulta real en la tablet de pruebas (rk3576, Android 14, `main` en `41db2df`, versión 1.4.0) por ONDOCTOR365. La API y la app de doctores se revisaron y andan: el problema es de la tablet. Objetivo del plan: **funcionalidad y simpleza**. Donde haya que elegir, va lo que tenga menos estados y menos casos especiales.

## Lo que pasó en la consulta (hechos del log)

Salió bien: un `POST` por segundo con la presión (108/53) y la temperatura (36,5), y el cierre de la consulta.

Fallaron tres cosas:

1. **El otoscopio no se publicó.**
   ```
   19:48:11  [PUSHER] start_oth
   19:48:14  [TELE] Otoscopio por UVC a 800×800 MJPEG, 30 cuadros/s, con una superficie que nadie ve
   19:48:19  [TELE] El otoscopio por UVC abrió pero no mandó ningún cuadro en 5 s: no se publica
   19:48:19  [TELE] La superficie del otoscopio por UVC no se pudo vaciar: UnsupportedOperationException:
             The producer output buffer format 0x1 doesn't match the ImageReader's configured buffer format 0x2.
   ```
   El otoscopio estaba en modo «Computer» y estable desde 17 s antes. Dos causas: `FIRST_FRAME_TIMEOUT_MS = 5_000` es corto (el primer cuadro tarda unos 5 s) y la superficie de `UvcOtoscopeFrames.drain` se crea en `RGBX_8888` (0x2) cuando libuvc entrega `RGBA_8888` (0x1): cada imagen tira, no se consume, y con `DRAIN_IMAGES = 2` el productor se queda sin lugar.

2. **El estetoscopio no se publicó.**
   ```
   19:41:12  (Android) adaptador USB Audio: disponible          ← se abrió el kiosco, puertos 1 y 2 encendidos
   19:47:45  (Android) adaptador USB Audio: NO disponible
   19:47:46  (Android) micrófono del otoscopio: disponible      ← el otoscopio terminó de presentarse
   19:48:54  [TELE] No se publica el estetoscopio: No aparece el adaptador del estetoscopio ("USB Audio")
   ```
   Android atiende **una sola placa de audio USB a la vez**. El otoscopio trae micrófono propio; con los dos puertos encendidos, el último que se presenta se queda con el lugar y el otro desaparece de `AudioManager` aunque siga enchufado (`/proc/asound/cards` muestra las dos). El proveedor Koplak tuvo lo mismo y lo resolvió no dejándolos encendidos a la vez.

3. **El sonido de la videollamada no salió por el parlante**, con «Altavoz» elegido (`call_audio_output = parlante`).
   - El registro de audio de Android muestra que la llamada del `WebView` se reproduce como `USAGE_MEDIA` y que el modo nunca dejó de ser `MODE_NORMAL`. `CallAudioRouter` sólo actúa con `MODE_IN_COMMUNICATION`: en esta tablet **el selector no hace nada**.
   - En modo normal manda Android: el último equipo enchufado. De 19:41 a 19:47 el sonido fue a la salida del adaptador del estetoscopio (no se oye); después, a los auriculares del jack.

## 1. Un solo equipo de audio USB encendido a la vez

Es el cambio que más simplifica: resuelve el punto 2 entero y la mitad del 3.

**Regla nueva.** El otoscopio y el estetoscopio **nunca están encendidos a la vez**, y en reposo están **apagados los dos, también con el kiosco adelante**. El termómetro sigue siempre encendido.

**Cambios.**

- `HubRestState.wanted` pierde el parámetro `kiosk`: otoscopio y estetoscopio apagados, termómetro encendido, siempre. Sale todo lo que existía para distinguir «con el kiosco» de «en reposo»: `kioskVisible` y `setKioskVisible` en `TotemRuntime` si no les queda otro uso (mirá el punto 3 antes de borrar: el audio de la llamada necesita saber si el kiosco está adelante), el botón de Testing que guarda «el estado del kiosco» como arranque del hub (que guarde el reposo), y sus textos, tests y README.
- Teleconsulta (`LiveKitBackend`): `publishOtoscope` y `publishStethoscope` encienden su puerto y, antes, **apagan el del otro** si está encendido (leyendo el hub, no sólo lo que encendió la teleconsulta). `TeleStreamer` ya despublica el instrumento anterior antes de publicar el nuevo; esto cubre además un puerto que quedó encendido por otra razón. Al despublicar, el puerto se apaga (con el reposo apagado, lo que enciende la teleconsulta es siempre suyo: revisá que `PanelPorts` no deje un puerto encendido «porque no se pudo leer» en este camino).
- Después de apagar el otro puerto, esperá a que Android suelte su placa de audio antes de buscar la entrada del estetoscopio: el `stethoInput` de hoy ya espera hasta `STETHO_WAIT_NANOS` a que aparezca; alcanza con que la espera corra también cuando se apagó el otro puerto.
- Panel Local: la misma regla. **Escuchar** con el otoscopio encendido lo apaga primero (suelta la cámara y después el puerto, como hace **Apagar**), y **Encender** el otoscopio con el estetoscopio escuchando lo detiene primero. Sin diálogos: el panel ya muestra el estado de cada uno.
- Regla pura y con test: dado el instrumento que se va a usar y el estado leído de los puertos, qué puertos se apagan y cuál se enciende.

## 2. El otoscopio en la teleconsulta

Con el punto 1, el puerto del otoscopio se enciende recién con `start_oth`, así que el otoscopio arranca en su menú y **alguien tiene que elegir «Computer» en su pantalla** (plan del PR 44). Eso lo hace reconectarse con otro `deviceId`.

**Cambios.**

- **Superficie.** `UvcOtoscopeFrames.drain`: `PixelFormat.RGBA_8888`. Corregí el comentario de la clase, que dice lo contrario.
- **Una sola espera, larga.** Desde que se abre, se espera el primer cuadro hasta `FIRST_FRAME_TIMEOUT_MS = 60_000` (hay que dar tiempo a mirar el otoscopio y elegir). Mientras se espera, si el `deviceId` del otoscopio cambia, se suelta y se abre de nuevo: es la regla `UvcOtoscope.Reconnect` del PR 44. **No la copies**: sacala a un lugar común (por ejemplo `OtoscopeReconnect` en `otoscope/`) y que la usen `UvcOtoscope` y `UvcOtoscopeFrames`, con el mismo test.
- Si el otoscopio desaparece del todo durante la espera (se apagó solo), se sigue esperando hasta el tope: vuelve cuando lo prenden.
- Un `stop_oth` o el cierre de la sala cortan la espera en el momento. Hoy la espera es un `await` que bloquea el hilo del streamer: mientras dura no se atiende ninguna orden. Hacelo cancelable (el `stop` tiene que poder interrumpirlo) sin sumar hilos nuevos si se puede.
- **Aviso en la tablet.** Mientras la teleconsulta espera la imagen del otoscopio, el kiosco muestra arriba de la web un cartel nativo, con el estilo del panel lateral: «Prendé el otoscopio y elegí «Computer» en su pantalla.» Se va solo cuando llega la imagen o se corta. Para el estetoscopio no hace falta cartel.
- `TeleStatus` suma lo necesario para que la pantalla lo sepa (por ejemplo `waitingImage: Boolean`), sin exponer más que eso.

## 3. El sonido de la llamada: que «Altavoz» y «Auricular» manden

**Idea.** En Android, con el modo en `MODE_IN_COMMUNICATION`, la reproducción de medios sigue al dispositivo de comunicación. Si Berry Med pone ese modo mientras el kiosco está adelante y fija el dispositivo con `setCommunicationDevice`, la llamada del `WebView` (que es `USAGE_MEDIA`) tiene que salir por donde dice el selector.

**Cambios en `CallAudioRouter`.**

- «Hay llamada» deja de ser `audio.mode == MODE_IN_COMMUNICATION` y pasa a ser **el kiosco está adelante** (lo avisa `KioskActivity`, como hoy avisa `setKioskVisible`). Con el kiosco adelante el router pone `audio.mode = MODE_IN_COMMUNICATION` y fija el dispositivo elegido; al salir del kiosco suelta el dispositivo y vuelve a `MODE_NORMAL`.
- Android puede devolver el modo a normal si la app dueña no está reproduciendo ni grabando. El router ya escucha `addOnModeChangedListener`: con el kiosco adelante, si el modo cambió, se vuelve a poner (con el freno de `CallReassert`, para no entrar en un ciclo).
- Chromium también llama a `setCommunicationDevice` por su cuenta (se ve en el registro, eligiendo los auriculares): `CallReassert` queda como está para reafirmar.
- Con «Auricular»: el jack si hay algo enchufado y, si no, el parlante (la regla de `AudioPick.callOutput` no cambia). Con «Altavoz»: el parlante aunque haya algo en el jack.
- El estetoscopio de la teleconsulta captura con `setPreferredInputDevice` del adaptador: comprobá en el código que el modo comunicación no le cambia la entrada (si `JavaAudioDeviceModule` reabre la captura al cambiar el dispositivo de comunicación, dejalo anotado). `LiveKitBackend` sigue con `NoAudioHandler`: el modo lo maneja el router, no LiveKit.
- `describe()` (Testing → Teleconsulta) tiene que decir la verdad con el modelo nuevo: el modo, quién lo puso y el dispositivo.
- Simplificá los comentarios largos de `CallAudioRouter` y de `HubRestState` que describen el modelo viejo (H390, «con el kiosco los dos encendidos»).

**La regla de sonido que tiene que quedar** (confirmada con el usuario el 15 de noviembre, después de escribir el plan):

- **La videollamada sale por el parlante de la tablet**, aunque haya auriculares enchufados en el jack. Es lo normal del armado: los auriculares del jack son para el estetoscopio.
- **El estetoscopio del Panel Local sale por el jack**, como hoy (`AudioPick.stethoOutput`: nunca por el parlante, que se acopla). No cambia.
- El estetoscopio de la teleconsulta no suena en la tablet: va al médico por LiveKit.
- Por eso el valor de fábrica de `callAudioOutput` pasa a **Altavoz** (`CALL_AUDIO_SPEAKER`): hoy es Auricular, y con auriculares en el jack la llamada se iría ahí. `callAudioOutputOrDefault` también: sin nada guardado, Altavoz. Una tablet que ya guardó una elección la conserva. El selector del panel lateral queda, con Altavoz primero si el orden es fácil de cambiar.
- El modo comunicación es sólo con el kiosco adelante. En el Panel Local el modo es normal y el estetoscopio sigue eligiendo el jack con su dispositivo preferido: comprobá que salir del kiosco al Panel Local (el cambio de modo del panel lateral) deja el modo en normal antes de que alguien toque **Escuchar**.
- Tests: el valor de fábrica y `callAudioOutputOrDefault` (los que hoy esperan Auricular cambian); con Altavoz y algo en el jack, `AudioPick.callOutput` da el parlante (ya debería existir: si no, sumalo).

**Esto hay que confirmarlo en la tablet** (no hay forma de probarlo en el emulador, que no enruta igual). Dejá un interruptor simple en un solo lugar (una constante) para volver al comportamiento de hoy si en la tablet no resulta, y decilo en el informe.

## 4. Que el log sirva después de una prueba

**Qué pasa hoy.** `[DATA]` escribe una línea por segundo. El registro de la tablet se llenó y de la consulta sólo quedaron los últimos 90 segundos: se perdieron el inicio, los puertos y el audio.

**Cambios.**

- `[DATA]`: con la consulta activa, una línea cada 10 s (la primera siempre, y siempre que cambie un valor de `vitalSigns`); los errores de envío, siempre. La consola de la pantalla principal puede seguir como está si usa otro camino; no la empeores.
- Un archivo de log propio, sin `[DATA]`: `files/logs/berry.log`, con rotación simple (1 MB, se conserva el anterior como `berry.1.log`). Todo lo que hoy va a `log()` menos `[DATA]`, con fecha y hora. Escritura en un hilo propio, sin frenar a quien llama. En el README, cómo sacarlo: `adb exec-out run-as com.guardiavirtual.berrymed cat files/logs/berry.log`.
- La regla de qué líneas van y cuándo rota, pura y con test.

## 5. El panel lateral dice lo mismo que la pantalla

- La fila del otoscopio dice «En vivo» apenas abre, aunque no haya imagen (en el Panel Local el encabezado ya dice «Esperando imagen»). Que la fila use el mismo criterio: «Esperando imagen» hasta que haya cuadros, en el Panel Local y en la teleconsulta.
- Con el punto 1, en el kiosco el otoscopio y el estetoscopio van a estar apagados casi siempre: la fila tiene que decir «Listo» (enchufado no se puede saber con el puerto apagado: es «el hub está y el puerto se puede encender»), no «Desconectado» en rojo. Mirá qué dice hoy `StatusPanelUi` para un puerto apagado en Remoto y dejalo coherente con Local.

## 6. El volumen en el panel lateral

Agregado el 15 de noviembre, pedido por el usuario: poder subir y bajar el volumen de la tablet desde el panel lateral, sin salir de Berry Med. El selector Auricular / Altavoz **queda** (el usuario quiere poder elegir).

**Cambios.**

- En la sección **Sonido** del panel lateral, debajo del selector, una barra de volumen con el estilo del panel: ícono a la izquierda (`volume_up`, `volume_down` o `volume_off` según el nivel), la barra y el porcentaje a la derecha. Ancho completo de la sección; que se pueda arrastrar con el dedo sin esfuerzo (alto táctil de 48 dp).
- Maneja el volumen de medios de Android (`AudioManager.STREAM_MUSIC`, con `setStreamVolume` sin mostrar la interfaz del sistema): es por donde suenan la videollamada del `WebView` (`USAGE_MEDIA`) y el estetoscopio del Panel Local. Confirmá en el código por qué stream sale el estetoscopio (`StethoscopeLink`); si fuera otro, la barra tiene que mover el que corresponda a lo que está sonando y decilo en el informe. Con el modo comunicación del punto 3 la reproducción sigue siendo `USAGE_MEDIA`: comprobá en el emulador que la barra sigue moviendo ese volumen con el kiosco adelante.
- Android guarda el volumen por dispositivo de salida (parlante, jack): la barra muestra el del que está sonando ahora y se actualiza cuando cambia el selector, cuando se enchufa o desenchufa algo y cuando alguien usa los botones físicos de la tablet (escuchar `android.media.VOLUME_CHANGED_ACTION` o un `ContentObserver` de los ajustes del sistema; lo que sea más corto).
- Las pantallas de Berry Med declaran `volumeControlStream = AudioManager.STREAM_MUSIC`, así los botones físicos mueven lo mismo que la barra.
- En el riel (panel cerrado) ya hay un ícono de sonido: que refleje el nivel (silenciado o no) y siga abriendo el panel.
- El volumen propio del estetoscopio del Panel Local (el número de 1 a 10) no cambia: es la ganancia de la escucha, otra cosa.
- La regla pura y con test: del índice de Android (0 a máximo) al porcentaje y al ícono, y del porcentaje arrastrado al índice.
- `README.md`: la barra en «Panel lateral».

## Lo que no entra

- Que la tablet vuelva a buscar el YKUSH3 si no apareció al arrancar: es un ajuste de sistema, lo hace la sesión principal.
- Bloquear el micrófono del otoscopio a nivel sistema (lo del proveedor): con el punto 1 no hace falta.
- La API y la app de doctores.
- La cadencia baja del otoscopio (5 a 9 cuadros por segundo).
- La versión: **no la cambies**.

## Tests y entrega

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde. Hoy hay 365 tests.
- Si el emulador `berry_tablet` está prendido (`adb devices`; usá siempre `adb -s emulator-5554`), instalá ahí y verificá lo que se pueda: que el kiosco abre, que Testing → Teleconsulta describe el audio, que el archivo de log se escribe y rota. Si no está prendido, no lo levantes. **La tablet real (`192.168.1.81:5555`) no se toca**: la prueba con hardware la hace la sesión principal.
- Documentación en el mismo PR: `README.md` (puertos del hub, teleconsulta, sonido de la llamada, el log), `docs/hardware.md` (un solo equipo de audio USB a la vez; el otoscopio y «Computer») y `docs/decisiones.md` (entrada del 15 de noviembre para el plan 18, con lo que queda por confirmar en la tablet).
- Rama `fix/teleconsulta-simple` desde `main`; PR contra `main`. Commits en español con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`, uno por punto si se puede. Commiteá este plan. No commitees `.claude/`.
- Informe final: qué cambió por punto con los archivos principales; qué borraste (este plan pide sacar piezas: decí cuáles salieron); qué decidiste donde el plan dejaba elegir; el resultado de gradle; y una **lista de pruebas para la tablet**, paso a paso, con la línea de log que confirma cada una.
