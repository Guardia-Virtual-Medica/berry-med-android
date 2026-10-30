# Hardware del tótem

Qué equipos lleva el tótem de Berry Med, cómo se conectan y cómo los reconoce la app. Complementa la sección "Equipos" del `README.md`. Los identificadores USB salen de los volcados que se tomaron con cada equipo; en el código están en `usb/KnownDevices.kt` y en `res/xml/usb_device_filter.xml`.

## Topología

Croquis de Mauricio (electrónico del proyecto), 28 de octubre de 2026.

| Nivel | Equipo | Se conecta a |
|---|---|---|
| 1 | Tablet Android (última versión) | — |
| 2 | Dock USB-C con carga ("PC" en el croquis) | Tablet |
| 3 | Plaqueta PM6750 | Dock |
| 3 | Termómetro infrarrojo | Dock |
| 3 | Hub YKUSH3 | Dock |
| 4 | Otoscopio | YKUSH3, puerto 1 |
| 4 | Adaptador de audio USB (con el estetoscopio en su jack) | YKUSH3, puerto 2 |
| 4 | Auricular Logitech H390 | YKUSH3, puerto 3 |

- Los puertos del YKUSH3 son los de fábrica de la app; se cambian en Configuración, paso Plaqueta (uno distinto para cada equipo).
- De fábrica, si no se guardó otro arranque, la placa arranca con los tres puertos encendidos (dato de la app: la hoja de datos no lo dice). El botón de Testing guarda el estado de reposo como arranque.
- Encender un puerto es "enchufar" el equipo para Android y apagarlo es desenchufarlo. La idea de Mauricio es que así el sistema conmute solo al equipo recién enchufado, lo que sirve también para Chrome y LiveKit en la teleconsulta.
- El hub se ve como hub USB 3.2, hub USB 2.0 y HID. Entra por USB 3.0 Micro-B; el jumper VBUS/VEXT elige la alimentación, con fuente externa recomendada de 5,00 a 5,25 V.
- El relé `USBRelay2` que cortaba los 12 V de la plaqueta ya no va: el firmware de la plaqueta se corrigió.

## Equipos

| Equipo | VID:PID | Clase USB | Cómo lo toma la app | Permiso USB |
|---|---|---|---|---|
| Plaqueta PM6750 (Berry) | `0483` (STM32) | Puerto serie virtual (CDC) | Puerto serie a 115200 baudios con tramas `55 AA`. Un puerto de ST que no contesta sigue siendo la plaqueta. | Sí |
| Termómetro infrarrojo | Variable (chip serie) | Puerto serie USB | El puerto serie que no es la plaqueta; queda abierto esperando. | Sí |
| Hub YKUSH3 (Yepkit, USB 3.0) | `04D8:F11B` | HID | `usb/YkushHub.kt` | Sí |
| Otoscopio | `349C:3301` | UVC + audio + almacenamiento | Camera2/CameraX si la tablet lo lista como cámara externa; si no, libuvc. | Sí |
| Adaptador de audio USB TTGK | `3302:29E3` | USB Audio Class 1.0 + HID | Por nombre de dispositivo de audio ("USB Audio" por defecto, configurable). | No |
| Auricular Logitech H390 | `046D` (PID probable `0A8F`) | USB Audio Class | Por nombre de dispositivo de audio ("H390", y si no "Logitech"; configurable). | No |

### Plaqueta PM6750

- STM32 con puerto serie virtual, fabricante `0483`.
- 115200 baudios, tramas que empiezan con `55 AA`.
- Manda ECG de 7 derivaciones, SpO₂ con su onda, respiración, presión no invasiva y temperatura de su propia sonda.
- Ya no necesita el relé de 12 V: el firmware se corrigió y no se traba.

### Termómetro infrarrojo

- Puerto serie USB. El chip puede variar; `usb_device_filter.xml` lista los fabricantes más comunes (WCH `1A86`, Silicon Labs `10C4`, FTDI `0403`, Espressif `303A`, Prolific `067B`, Arduino `2341`/`2A03`, Raspberry Pi `2E8A`, Microchip `04D8`).
- Sólo manda una línea cuando alguien aprieta el gatillo. No tiene comando de "medir", así que no se lo puede reconocer por lo que transmite ni pedirle una lectura.

### Hub YKUSH3

HID `04D8:F11B` (Microchip es también fabricante de chips serie; por eso el filtro USB lo cubre por fabricante). Protocolo según la hoja de datos (v1.2.1, enero de 2019):

| Paquete | Qué hace |
|---|---|
| `0x11`, `0x12`, `0x13` (repetido en el segundo byte) | Enciende el puerto 1, 2 o 3 |
| `0x01`, `0x02`, `0x03` (repetido en el segundo byte) | Apaga el puerto 1, 2 o 3 |
| `0x21`, `0x22`, `0x23` (repetido en el segundo byte) | Consulta el puerto 1, 2 o 3 |
| `0x41`, puerto, `0x00` o `0x01` | Guarda cómo arranca ese puerto: apagado o encendido (sección 6.6) |

- Paquetes de 64 bytes por la interfaz HID.
- Respuesta: `0x01` en el primer byte si salió bien; al consultar, `0x1N` encendido o `0x0N` apagado en el segundo.
- `0x2A` (consultar los tres) no está en la hoja y pykush sólo lo usa con firmware 2 o superior: la app consulta de a uno. Un puerto que no contesta queda sin leer y los demás se conservan.
- Cada comando espera hasta 1 s la respuesta y leer los tres hasta 3 s: la app los manda fuera del hilo de la pantalla.
- `0x41` queda en la memoria del hub y no se puede leer: nunca se manda solo, sólo con el botón **Guardar este arranque en el hub** de Testing.
- Estado de reposo: otoscopio y estetoscopio apagados; auricular encendido si la videollamada sale por él, apagado si sale por el parlante. El servicio lo aplica la primera vez que el hub se puede usar y al guardar otra salida de audio, salvo con una consulta enviando, una prueba de Testing, el Panel Local abierto o la teleconsulta publicando: en ese caso espera a que se libere. Después los cambian Testing, el Panel Local y la teleconsulta, que sólo apagan los que encendieron.

### Otoscopio

| Dato | Valor |
|---|---|
| VID:PID | `349C:3301` |
| Nombre | "Generic / HD video" |
| Velocidad | USB 2.0 high-speed |
| Consumo | 256 mA |
| Video | Video Control + Video Streaming, MJPEG cuadrado: 480², 800², 960², 1280², 1440², a 30 o 5 fps |
| Audio | Micrófono USB Audio mono, 16 bits, 32 kHz (no se usa) |
| Almacenamiento | Mass Storage SCSI (no se usa) |

- Encender su puerto en el YKUSH3 lo enchufa para Android y prende su luz.
- La app pide 800×800 (o la más cercana): a 1440² se come el ancho de banda del USB 2.0 que comparte con la plaqueta.
- Chrome en Android no lo ve como cámara salvo que el fabricante de la tablet exponga las cámaras USB por Camera2. Por eso la app tiene dos caminos y elige solo:
  - **Camera2/CameraX**, si la tablet lo lista como cámara externa (`LENS_FACING_EXTERNAL`).
  - **UVC**, directo por USB con su permiso, con el módulo libuvc de AndroidUSBCamera (AUSBC) 3.2.7.

### Estetoscopio Dalia StethoAI y adaptador de audio

- El estetoscopio es un micrófono analógico con plug TRRS de 3,5 mm; no es USB. Su manual sólo habla de PC, Mac e iOS.
- Va al jack del **adaptador de audio USB TTGK**:

| Dato | Valor |
|---|---|
| VID:PID | `3302:29E3` |
| Nombre | "USB Audio" |
| Clase | USB Audio Class 1.0 |
| Entrada | Mono, 16 bits, 48 kHz (la del estetoscopio) |
| Salida | Estéreo, 44,1 o 48 kHz (no se usa) |
| Otros | HID de volumen |
| Velocidad | Full-speed |
| Consumo | 100 mA |

- Android lo maneja nativo, sin permiso USB. La app lo elige por nombre ("USB Audio" por defecto, configurable en Configuración → Plaqueta → Estetoscopio).

### Auricular Logitech H390

- USB Audio Class, VID `046D` (PID probable `0A8F`, sin confirmar).
- Tiene micrófono propio, que nunca se usa como estetoscopio aunque su nombre coincida.
- La app lo elige por nombre: "H390" y, si no lo encuentra, "Logitech" (configurable).
- La salida del estetoscopio va sólo al auricular: por el parlante de la tablet se acopla con el estetoscopio.
- Es la salida por defecto de la videollamada; en Configuración → Kiosco se cambia por el parlante.

## Permisos USB

| Equipo | En `usb_device_filter.xml` | Por qué |
|---|---|---|
| Plaqueta PM6750 | Sí (fabricante `0483`) | La app abre el puerto serie. |
| Termómetro | Sí (por fabricante de chip serie) | La app abre el puerto serie. |
| Hub YKUSH3 | Sí (fabricante `04D8`) | La app manda órdenes HID. |
| Otoscopio | Sí (`349C:3301`) | El camino UVC abre el dispositivo directo. |
| Adaptador de audio | No | Android lo maneja como audio; pedir permiso sólo pondría un diálogo de más arriba de Chrome. |
| Auricular H390 | No | Ídem. |

Con "Usar de forma predeterminada" marcado, Android da el permiso solo cada vez que el equipo aparece, también después de un reinicio.

## Riesgos a confirmar en la tablet real

El emulador no los muestra. Se revisan con Testing → "Dispositivos que ve Android" y con `adb logcat -s BerryMed` (ver "Probar el Panel Local en el tótem" en el `README.md`).

| Riesgo | Qué mirar |
|---|---|
| Si el otoscopio aparece como cámara externa (Camera2) o sólo como USB (UVC) | En Testing, Cámaras: "externa (USB)". En el panel, el encabezado del otoscopio dice el camino. |
| Si las dos placas de audio USB conviven y con qué nombres aparecen | En Testing, los dispositivos de audio: tienen que estar el adaptador y el auricular. Anotar los nombres para la configuración. |
| Ancho de banda isócrono con otoscopio y audio a la vez en el mismo hub | Encender el otoscopio y escuchar el estetoscopio al mismo tiempo: que no se corte la imagen, el sonido ni la plaqueta. |
| Que `setPreferredDevice` realmente enrute al adaptador y al auricular | Que el sonido salga por el H390 y no por el parlante, y que la onda sea la del estetoscopio y no la del micrófono del auricular. |
| Permiso USB del otoscopio negado sin diálogo (Android 10 o más) si la app todavía no tiene el permiso de cámara | Aceptar el permiso de cámara en el Panel Local antes del permiso USB del otoscopio. |
| El otoscopio por UVC sin vista para la teleconsulta (libuvc entrega cuadros sin superficie donde dibujar) | Testing → Teleconsulta: "otoscopio por UVC" con cuadros por segundo mayores a 0, y el médico ve la imagen. |
| Camera2 para la teleconsulta mientras Zoom usa la cámara de la tablet (límite de cámaras abiertas a la vez) | Por eso se prefiere UVC; si la tablet lo lista por Camera2, que el médico vea el otoscopio y el paciente siga viéndose en Zoom. En el emulador, con una sola cámara, abrir el otoscopio le sacó la cámara al `WebView`. |
| Que el adaptador ofrezca `UNPROCESSED` | La línea `[TELE] Estetoscopio por …` dice la fuente. |
| Que Android no silencie la captura del estetoscopio con Zoom en la llamada dentro del kiosco | Testing → Teleconsulta: "la captura no está silenciada". |
| Que el `WebView` entre en modo comunicación con Zoom. En el emulador (WebView 124, Android 15) una llamada WebRTC de prueba quedó en `MODE_NORMAL`, captura `CAMCORDER` y reproducción `USAGE_MEDIA`, y `CallAudioRouter` no actuó: Chromium fijó el dispositivo solo. Sin modo comunicación el selector no surte efecto | Testing → Teleconsulta: "modo comunicación" durante la llamada, y una línea `[LLAMADA]`. Con `adb shell dumpsys audio`, `Actual mode` y el `usage` del reproductor del `WebView`. |
| Si Zoom reproduce con uso de medios y no de comunicación, `setCommunicationDevice` no lo mueve: Android elige la salida de medios y puede ser cualquier USB | Con Parlante elegido y el H390 encendido, ¿por dónde se oye al médico? Con Auricular, que no salga por el jack del adaptador. |
| Que el puerto del estetoscopio, al encenderse en plena llamada, se lleve la salida de medios del `WebView` | Publicar el estetoscopio durante la llamada: que se siga oyendo al médico por la misma salida. |
| Que el micrófono del otoscopio no coincida con el nombre del estetoscopio | En Testing, los dispositivos de audio: el nombre configurado (`USB Audio`) tiene que identificar sólo al adaptador. |
| Que el hub acepte y guarde `0x41` | Testing → **Guardar este arranque en el hub**: tiene que decir que guardó. Después cortar la alimentación del hub y ver con qué puertos arranca. |
| Que Zoom Video SDK ande en el `WebView` (cookies, `SharedArrayBuffer`, navegador) | Que la videollamada se vea y se escuche en el kiosco de Berry Med; líneas `[KIOSCO]`. |

## El micrófono compartido

En la teleconsulta la llamada de Zoom tiene el micrófono de la tablet abierto (`VOICE_COMMUNICATION`, con el audio en `MODE_IN_COMMUNICATION`). Android (`AudioPolicyService::updateUidStates_l`) no deja capturar a **otra app** mientras la dueña del modo de comunicación está capturando: le da **silencio**, aunque capture de otro dispositivo, como el adaptador del estetoscopio. Con Zoom en Chrome, el estetoscopio de Berry Med quedaba mudo.

- **Resuelto compartiendo la app**: el kiosco (y con él Zoom) corre dentro de Berry Med, así que la captura de la llamada y la del estetoscopio son del mismo UID y Android no silencia ninguna. No hace falta que el kiosco suelte el micrófono. En el emulador, con una llamada WebRTC abierta en el `WebView`, las dos capturas quedaron `silenced:false`.
- Berry Med no toca el modo, el foco ni el ruteo del audio: el `WebView` maneja los de la llamada.
- Testing → Teleconsulta (LiveKit) muestra si Android silencia la captura (`AudioRecordingConfiguration.isClientSilenced()`); en `adb shell dumpsys media.audio_policy` se ve el estado de cada captura.
- Cámara y micrófono desde segundo plano: el servicio de Berry Med tiene que haberse puesto en primer plano como `camera|microphone` con una pantalla de Berry Med visible (ver el `README.md`). El fabricante de la tablet puede tener reglas más duras.

## Diagrama

```
Tablet Android
  |
  | USB-C
  v
Dock USB-C con carga ("PC")
  |
  +-- Plaqueta PM6750 ........ STM32, 0483, serie 115200, tramas 55 AA
  |
  +-- Termómetro IR .......... serie USB, manda sólo al apretar el gatillo
  |
  +-- Hub YKUSH3 ............. HID 04D8:F11B
        |
        +-- Puerto 1: Otoscopio ............. UVC 349C:3301, MJPEG 800x800
        |
        +-- Puerto 2: Adaptador audio TTGK .. 3302:29E3, "USB Audio"
        |                 |
        |                 +-- jack 3,5 mm TRRS: Estetoscopio Dalia StethoAI
        |
        +-- Puerto 3: Auricular Logitech H390 (046D), salida del estetoscopio y de la llamada
```
