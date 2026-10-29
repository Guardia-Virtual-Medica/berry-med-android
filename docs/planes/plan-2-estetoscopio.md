# Plan 2: estetoscopio en el Panel Local

Plan escrito para que lo ejecute un agente. Hecho: ver `docs/decisiones.md`.

## Qué es

Agregar al Panel Local (ver `README.md`, sección "Panel Local", y `docs/planes/plan-1-panel-local.md`) una tarjeta de **estetoscopio**: escuchar el sonido por el auricular y ver la onda en vivo. Sin grabar, sin análisis, sin guardar.

## El hardware, ya definido

- **Estetoscopio Dalia StethoAI**: un micrófono analógico con plug TRRS de 3,5 mm. No es USB.
- **Adaptador de audio USB** (TTGK, `3302:29E3`): el estetoscopio va enchufado a su jack. Para Android es una placa de audio USB Audio Class 1.0 con una entrada mono de 16 bits a 48 kHz (también tiene salida estéreo, que no usamos). Cuelga del **puerto 2 del hub YKUSH3**.
- **Auricular Logitech H390**: USB, con salida estéreo y un micrófono propio que se ignora. Cuelga del **puerto 3 del YKUSH3**.
- **Hub YKUSH3**: `usb/YkushHub.kt` ya enciende y apaga cada puerto; `BerryConfig.ykushStethoscopePort` y `ykushHeadsetPort` dicen cuál es cuál (por defecto 2 y 3).

Android maneja las dos placas de audio USB sin drivers: aparecen en `AudioManager.getDevices()` como `TYPE_USB_DEVICE` o `TYPE_USB_HEADSET`. No hace falta permiso USB para ellas (y por eso no están en `usb_device_filter.xml`: no lo agregues).

## Lo que la tablet real tiene que confirmar (no cambia el armado)

Android moderno sostiene dos placas de audio USB a la vez si la app elige cada una a mano con `setPreferredDevice`. En Testing, "Dispositivos que ve Android" lista los dos cuando están enchufados. Si en la tablet apareciera uno solo, el camino alternativo es el auricular por Bluetooth; el plan deja la elección del dispositivo de salida configurable para no tener que tocar código en ese caso.

## Diseño

### Elegir los dispositivos de audio

Una clase pura `audio/AudioPick.kt` (sin Android) que, dada una lista de descriptores (tipo, nombre del producto, es entrada, es salida, id), elige:

- **la entrada del estetoscopio**: un dispositivo USB de entrada cuyo nombre contiene `config.stethoInputName` (por defecto `"USB Audio"`, el nombre que declara el adaptador TTGK); si hay varios, el que no sea el auricular;
- **la salida del auricular**: un dispositivo USB de salida cuyo nombre contiene `config.headsetName` (por defecto `"H390"`; si no lo encuentra, probar `"Logitech"`).

Los dos nombres van a `BerryConfig`, `ConfigStore` y al formulario de `MainActivity`, en el paso Plaqueta bajo un título "Estetoscopio", con ayuda que diga que vienen de Testing → Dispositivos. Nunca usar el micrófono ni el parlante por defecto: con el micrófono del otoscopio y el del H390 enchufados, el "por defecto" es cualquiera.

En Android, `AudioManager.getDevices(GET_DEVICES_ALL)` da `AudioDeviceInfo`; `DeviceInventory` ya los lista para Testing, reusá su `typeName` y `isUsb`.

### Captura, filtro y reproducción

`audio/StethoscopeLink.kt` (Android), que vive en `TotemRuntime` como `stetho`:

- `start(): String?` devuelve el motivo si no puede: sin permiso de micrófono, sin adaptador, sin auricular. Si falta el auricular **no reproduce** (el parlante de la tablet se acopla con el estetoscopio) pero sí captura y muestra la onda, y avisa "Sin auricular: sólo se ve la onda".
- Captura con `AudioRecord` a 48 kHz, mono, PCM 16 bits, `MediaRecorder.AudioSource.UNPROCESSED` si el dispositivo lo soporta (`AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`) y si no `VOICE_RECOGNITION` (sin cancelación de eco ni AGC, que arruinan el sonido cardíaco), `setPreferredDevice(entrada)`. Buffer de 20 ms. Hilo propio `berry-stetho`.
- Filtro pasa-banda 20 a 500 Hz: dos biquads (pasa-altos de 20 Hz y pasa-bajos de 500 Hz, Butterworth de segundo orden) en una clase pura `audio/BandPass.kt`, más una ganancia x1 / x2 / x4 elegible desde la tarjeta y un recorte suave para que no sature.
- Reproducción con `AudioTrack` a 48 kHz mono, `setPreferredDevice(salida)`, modo streaming, `AudioAttributes` de `USAGE_MEDIA`. Latencia esperada de unos 50 a 100 ms, suficiente para auscultar.
- Para la onda: un `LiveFeed.Channel` nuevo en `LiveFeed` (`stetho`, capacidad 5000) al que se empuja una muestra cada 96 (500 por segundo): el valor de mayor módulo del bloque, con signo, centrado en 32768 para que `WaveformView` lo dibuje como las otras. Un medidor de nivel: RMS del último bloque, expuesto como `Float` volátil.
- `stop()` cierra todo. `stopLocal()` del runtime lo llama siempre.

### El YKUSH3

Mauricio usa el encendido de cada puerto para que Android "enchufe" el equipo cuando se lo necesita. En el panel:

- **Escuchar**: en un hilo de trabajo, `ykush.setPort(puerto del estetoscopio, on)` y `setPort(puerto del auricular, on)` si están apagados, esperar hasta 5 s a que aparezcan los dos dispositivos de audio (`AudioManager.registerAudioDeviceCallback` o sondeo cada 250 ms), y recién ahí `stetho.start()`.
- **Parar**: `stetho.stop()`; los puertos quedan como están.
- Al cerrar el panel: `stetho.stop()` y apagar los dos puertos si el panel los encendió (recordá en el runtime qué encendió el panel). Si no hay YKUSH3 enchufado, se saltea la parte del hub y se intenta directo.
- Las llamadas al YKUSH3 bloquean hasta 1 s: nunca en el hilo de la pantalla.

### Permiso

`RECORD_AUDIO` en el manifest y pedido en tiempo de ejecución al tocar Escuchar, igual que la cámara en `LocalPanelActivity`. Si lo niegan, mensaje en la tarjeta.

### La tarjeta

En la columna derecha de `activity_local_panel.xml`, entre la cámara y la presión (o debajo de la presión si no entra: medí en un layout apaisado de 1280×800 dp): título "Estetoscopio", `WaveformView` de ~64 dp con el canal `live.stetho`, un medidor de nivel (una barra horizontal simple), botones **Escuchar** / **Parar** y **Ganancia** (x1 → x2 → x4), y una línea de mensaje. La tarjeta se oculta (`GONE`) si no hay adaptador ni YKUSH3 enchufados, y vuelve cuando aparecen (el `render()` de 500 ms ya existe).

## Tests (JUnit, sin Android)

- `BandPass`: una senoidal de 100 Hz pasa con amplitud casi igual; 5 Hz y 2 kHz quedan atenuadas más de 20 dB; una constante (DC) tiende a cero.
- Decimación a 500 por segundo: bloque de 96 muestras → una, con el signo del valor de mayor módulo; RMS correcto.
- `AudioPick`: elige el adaptador por nombre entre varios USB; prefiere el que no es el auricular; sin coincidencia devuelve null; elige el H390 por nombre y cae a "Logitech".
- Secuencia del YKUSH3 extraída a una clase pura (qué puertos encender según el estado actual, qué apagar al cerrar según lo que encendió el panel).
- Los tests existentes siguen pasando (`SessionGateTest`, `LiveFeedTest`, etc.).

## Criterios de aceptación

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.
- Con el panel cerrado no queda ningún `AudioRecord` ni `AudioTrack` abierto, ni hilo `berry-stetho` vivo.
- Nada de esto cambia el envío a la API ni el comportamiento con Chrome adelante: el estetoscopio sólo existe dentro del panel.
- README: en "Panel Local", qué hace la tarjeta, que necesita el auricular para sonar, el permiso de micrófono, y los dos nombres configurables.

## Entrega

Igual que el plan 1: rama `feat/estetoscopio-otoscopio` desde `main` (el plan 3 va después en la misma rama, como segundo commit, y los dos salen en un solo PR), commits en español en el estilo del repo con `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`, `git add` con rutas explícitas (sin `docs/`), PR contra `main` con "Qué trae", "Qué no hace" y "Cómo se probó", y el pie `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. No mergear. Decir en el PR que no se probó con el hardware y listar qué mirar: que Testing muestre las dos placas USB, que Escuchar encienda los puertos y suene por el H390 sin acople, que la onda se mueva con el estetoscopio en el pecho, y qué ganancia hace falta.
