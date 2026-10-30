# Plan 7: salida de audio de la videollamada y puertos del YKUSH3

Plan para un agente (30 oct 2026). Sale de comparar la hoja de datos del YKUSH3 (v1.2.1, enero 2019) con el código. El usuario pidió: "Deberíamos poder elegir desde donde escuchamos el audio, pero con los auriculares por defecto". Sin cambios en la API ni en el kiosco (`gvm.mt.totem`).

## Decisiones

- La videollamada se escucha por defecto en el auricular H390. En la configuración se puede cambiar por el parlante de la tablet.
- El selector es de la videollamada del kiosco. **No** se aplica al estetoscopio del Panel Local: ése sigue saliendo sólo por el auricular, porque por el parlante se acopla.
- El micrófono de la llamada acompaña a la salida: Android los mueve juntos con el dispositivo de comunicación. Con el H390 se habla por el H390; con el parlante, por el micrófono de la tablet.
- Estado de los puertos en reposo: otoscopio apagado y estetoscopio apagado. Auricular encendido si la salida es el auricular, apagado si es el parlante.
- Si la salida elegida es el auricular y no está, la llamada sale por el parlante y queda en el log y en Testing. Nunca se pierde el audio.

## Parte 1: protocolo del YKUSH3 (`usb/YkushHub.kt`)

1. `readAll` deja de usar `0x2A`: la hoja de datos no lo documenta y pykush sólo lo usa con firmware 2 o superior (al resto le consulta puerto por puerto y lo llama "firmware glitch workaround"). Se consulta cada puerto con `0x21`, `0x22`, `0x23`: respuesta byte 0 = `0x01`, byte 1 = `0x1N` encendido o `0x0N` apagado. Si un puerto falla, ese estado queda null y los demás se conservan. Hoy el contrato de la función es "bloquea hasta un segundo": pasa a ser hasta tres; revisar quién la llama desde qué hilo. Se quita `STATUS_ALL`.
2. Cada orden repite el comando en el byte 1, como pide la hoja de datos (tablas 2, 4 y 6). pykush manda cero y anda, así que es por seguir la hoja: no cambia nada más.
3. Comando nuevo `setDefaultState(port, on)`: `0x41`, puerto, `0x00` o `0x01` (sección 6.6). La respuesta trae `0x01` en el byte 0 si salió bien. Es persistente en el hub: **nunca** se manda solo, porque la hoja no ofrece leerlo y cada arranque gastaría la memoria del hub. Sólo con el botón de Testing.

## Parte 2: puertos en reposo

- Función pura (por ejemplo `service/HubRestState.kt`) que dada la configuración devuelve el estado deseado de cada puerto (lista de tres `Boolean`). Un puerto "libre" (sin equipo) queda como está: `null`.
- En `TotemRuntime`, la primera vez que el hub es utilizable (con permiso) para cada `deviceId`, se llevan los puertos a ese estado. Se saltea entero si hay una sesión enviando, una prueba de Testing, el Panel Local abierto o la teleconsulta publicando: no se le apaga un equipo a nadie. Un hub que recién tiene permiso más tarde tiene que entrar igual: engancharlo donde ya se revisa el USB, sin hilos nuevos. Cambia la regla "el servicio no toca los puertos solo": actualizar el KDoc de `YkushHub`, `PanelPorts`, el README y `docs/hardware.md`.
- `PanelPorts` no cambia: con los puertos apagados en reposo, el panel y la teleconsulta los leen apagados, los encienden, los anotan y los apagan al terminar. El auricular encendido en reposo no se anota, así que queda encendido.
- Al guardar la configuración con otra salida de audio se vuelve a aplicar el estado de reposo (con los mismos saltos).
- Testing, sección del hub: botón **Guardar este arranque en el hub**, que manda `0x41` a los tres puertos según el estado de reposo. Cubre los segundos entre que se prende el hub y arranca el servicio. Con un puerto libre, no se manda nada para ese puerto. Dice en pantalla si el hub aceptó o no.

## Parte 3: salida de la llamada

Hechos de Chromium (`media/base/android/.../CommunicationDeviceSelectorPostS.java` y `AudioManagerAndroid.java`):

- Con el micrófono en modo comunicación, el `WebView` pone `MODE_IN_COMMUNICATION` y usa `setCommunicationDevice`.
- Agrupa todo lo USB como un único `ID_USB_AUDIO` y toma el primero de `getAvailableCommunicationDevices()`: no distingue el H390 del adaptador del estetoscopio. Librado a sí mismo, puede sacar la llamada por el jack del estetoscopio.
- Al cerrar el modo comunicación llama a `clearCommunicationDevice`, y cuando cambian los dispositivos puede volver a elegir.

Por eso Berry Med fija el dispositivo y lo reafirma. Queda dentro del mismo UID que el `WebView`.

- Campo nuevo en `BerryConfig`: `callAudioOutput`, con `CALL_AUDIO_HEADSET` (por defecto) y `CALL_AUDIO_SPEAKER`, guardado en `ConfigStore`. Un valor desconocido o vacío se lee como auricular.
- En `audio/AudioPick.kt` (o junto a él), función pura `callOutput(devices, choice, headsetName)` sobre la lista de dispositivos de comunicación: con auricular, el USB cuyo nombre coincide con `headsetName` (misma regla que `headsetOutput`, y si no, el parlante); con parlante, el `TYPE_BUILTIN_SPEAKER`. **Nunca** el adaptador del estetoscopio, aunque sea el único USB.
- Clase nueva `audio/CallAudioRouter.kt`, API 31 o más (en menos, no hace nada y lo deja en el log). Con el proceso vivo escucha `addOnModeChangedListener`, `addOnCommunicationDeviceChangedListener` y `registerAudioDeviceCallback`. Si el modo es `MODE_IN_COMMUNICATION` y el dispositivo actual no es el elegido, llama a `setCommunicationDevice`. Fuera de la llamada no hace nada: no toca el modo ni el foco. Si Chromium y Berry Med se pisan no debe quedar un ciclo: no reafirmar más de una vez por segundo y dejar de insistir tras varios intentos seguidos sin éxito, con una línea `[LLAMADA]` en el log.
- Agregar `MODIFY_AUDIO_SETTINGS` al manifiesto propio: hoy llega sólo por la fusión con LiveKit y ahora dependemos de él.
- Lo que Testing muestra en la sección de Teleconsulta: salida elegida y dispositivo de comunicación actual.

## Parte 4: pantalla

Paso **Kiosco** del configurador, debajo de la URL: "Audio de la videollamada", con dos opciones (Auricular, Parlante de la tablet) y una ayuda: "El estetoscopio del Panel Local sale siempre por el auricular". Mismo estilo que el resto del formulario. Se guarda con el resto.

## Tests

- `YkushHub`: el paquete repite el comando en el byte 1; respuestas de estado por puerto, incluida una fallida que deja sólo ese null; paquete de `0x41`.
- `HubRestState`: las dos salidas y un puerto libre.
- `callOutput`: auricular presente, ausente (parlante), parlante elegido, y el adaptador nunca elegido.
- Lectura de `callAudioOutput` con valor desconocido.
- Si la decisión de reafirmar se puede separar sin Android, testearla (límite por segundo, tope de intentos).
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.

## Verificación

En el emulador `berry_tablet` (`emulator-5554`, adb en `~/Library/Android/sdk/platform-tools/adb`): el paso Kiosco con el selector, que guarde y vuelva a leer, y con una llamada WebRTC en el `WebView` (como en el plan 6) que `adb shell dumpsys audio` muestre el dispositivo de comunicación en el parlante con esa opción. El emulador no tiene hub ni H390: decirlo, no darlo por probado. Capturas en `docs/capturas/` (no se commitean).

## Documentación

- README: selector, estado en reposo, botón de Testing, y corregir "Berry Med no toca el modo, el foco ni el ruteo del audio" (el modo y el foco siguen sin tocarse; el dispositivo de la llamada sí).
- `docs/hardware.md`: tabla de comandos según la hoja de datos (sin `0x2A`, con `0x41`); el hub se ve como hub USB 3.2, hub USB 2.0 y HID (la hoja no menciona ningún ESP32: sacar esa línea); jumper VBUS/VEXT con fuente externa de 5,00 a 5,25 V recomendada, y entrada USB 3.0 Micro-B; "la placa arranca con los tres encendidos" pasa a ser lo que hace de fábrica si no se guardó otro arranque (dato de la app, la hoja no lo dice). Riesgos nuevos en la tabla: que el puerto del estetoscopio al encenderse en plena llamada se lleve la salida de medios del `WebView`; si Zoom reproduce con uso de medios y no de comunicación, `setCommunicationDevice` no lo mueve; que el micrófono del otoscopio no coincida con el nombre del estetoscopio; que el hub acepte y guarde `0x41`.
- `docs/decisiones.md`: entrada con estas decisiones.
- Commitear este plan.

## Entrega

Rama `feat/audio-llamada-y-puertos` desde `main`, commits en español en el estilo del repo, PR contra `main`. No tocar `local.properties`, `docs/capturas/` ni `docs/apk/`.
