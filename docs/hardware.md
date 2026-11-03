# Hardware del tótem

Qué equipos lleva el tótem de Berry Med, cómo se conectan y cómo los reconoce la app. Complementa la sección "Equipos" del `README.md`. Los identificadores USB salen de los volcados que se tomaron con cada equipo; en el código están en `usb/KnownDevices.kt` y en `res/xml/usb_device_filter.xml`.

## Topología

Croquis de Mauricio (electrónico del proyecto), 28 de octubre de 2026; armado validado de nuevo con él el 3 de noviembre de 2026 (plan 13): el termómetro pasa al puerto 3 del hub y el H390 sale.

| Nivel | Equipo | Se conecta a |
|---|---|---|
| 1 | Tablet Android (última versión) | — |
| 2 | Dock USB-C con carga ("PC" en el croquis) | Tablet |
| 3 | Plaqueta PM6750 | Dock |
| 3 | Hub YKUSH3 | Dock |
| 4 | Otoscopio | YKUSH3, puerto 1 |
| 4 | Adaptador de audio USB (con el estetoscopio en su jack) | YKUSH3, puerto 2 |
| 4 | Termómetro infrarrojo | YKUSH3, puerto 3 (siempre encendido) |
| — | Auriculares con cable | Jack de 3,5 mm de la tablet |

- Los puertos del YKUSH3 son los de fábrica de la app; se cambian en Configuración, paso Plaqueta (uno distinto para cada equipo).
- De fábrica, si no se guardó otro arranque, la placa arranca con los tres puertos encendidos (dato de la app: la hoja de datos no lo dice). El botón de Testing guarda el estado del kiosco como arranque, con el termómetro encendido.
- Encender un puerto es "enchufar" el equipo para Android y apagarlo es desenchufarlo. La idea de Mauricio es que así el sistema conmute solo al equipo recién enchufado, lo que sirve también para Chrome y LiveKit en la teleconsulta.
- El hub se ve como hub USB 3.2, hub USB 2.0 y HID. Entra por USB 3.0 Micro-B; el jumper VBUS/VEXT elige la alimentación, con fuente externa recomendada de 5,00 a 5,25 V.
- El relé `USBRelay2` que cortaba los 12 V de la plaqueta ya no va: el firmware de la plaqueta se corrigió.

## Equipos

| Equipo | VID:PID | Clase USB | Cómo lo toma la app | Permiso USB |
|---|---|---|---|---|
| Plaqueta PM6750 (Berry) | `0483` (STM32) | Puerto serie virtual (CDC) | Puerto serie a 115200 baudios con tramas `55 AA`. Un puerto de ST que no contesta sigue siendo la plaqueta. | Sí |
| Termómetro infrarrojo | Variable (chip serie) | Puerto serie USB | El puerto serie que no es la plaqueta; queda abierto esperando. Va en el puerto 3 del hub, siempre encendido. | Sí |
| Hub YKUSH3 (Yepkit, USB 3.0) | `04D8:F11B` | HID | `usb/YkushHub.kt` | Sí |
| Otoscopio | `349C:3301` | UVC + audio + almacenamiento | Camera2/CameraX si la tablet lo lista como cámara externa; si no, libuvc. | Sí |
| Adaptador de audio USB TTGK | `3302:29E3` | USB Audio Class 1.0 + HID | Por nombre de dispositivo de audio ("USB Audio" por defecto, configurable). | No |
| Auricular USB de respaldo (ya no va en el armado) | Variable | USB Audio Class | Por nombre de dispositivo de audio ("H390", y si no "Logitech"; configurable). | No |

### Plaqueta PM6750

- STM32 con puerto serie virtual, fabricante `0483`.
- 115200 baudios, tramas que empiezan con `55 AA`.
- Manda ECG de 7 derivaciones, SpO₂ con su onda, respiración, presión no invasiva y temperatura de su propia sonda.
- Ya no necesita el relé de 12 V: el firmware se corrigió y no se traba.

### Termómetro infrarrojo

- Puerto serie USB. El chip puede variar; `usb_device_filter.xml` lista los fabricantes más comunes (WCH `1A86`, Silicon Labs `10C4`, FTDI `0403`, Espressif `303A`, Prolific `067B`, Arduino `2341`/`2A03`, Raspberry Pi `2E8A`, Microchip `04D8`).
- Sólo manda una línea cuando alguien aprieta el gatillo. No tiene comando de "medir", así que no se lo puede reconocer por lo que transmite ni pedirle una lectura.
- Va en el puerto 3 del YKUSH3 (plan 13), que queda **siempre encendido**, con y sin kiosco, en el Panel Local y en la videollamada: sus lecturas tienen que llegar en los dos. Si el puerto se enciende tarde, `ThermometerLink` lo toma solo: busca candidatos cada 2 s y `requestMissingPermissions` le pide el permiso USB cuando aparece (el filtro ya incluye los fabricantes de los chips serie).

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
- Estado con el kiosco (plan 12): mientras `KioskActivity` está visible, otoscopio y estetoscopio encendidos. Es sólo energía: la transmisión empieza con `start_oth`/`start_steth`. `0x41` guarda este estado, porque la tablet arranca en el kiosco.
- Estado de reposo, con cualquier otra pantalla adelante: otoscopio y estetoscopio apagados. En los dos, el termómetro encendido (plan 13); la salida de audio de la llamada ya no cambia ningún puerto. El servicio aplica el que toque la primera vez que el hub se puede usar y al abrir o dejar el kiosco. Con una consulta enviando, una prueba de Testing, el Panel Local abierto o la teleconsulta publicando, sólo manda el encendido del termómetro, una vez por hub (`HubRestState.changes` con `blocked`): encender no le saca nada a nadie. Lo demás espera a que se libere. Después los cambian Testing, el Panel Local y la teleconsulta, que sólo apagan los que encendieron y nunca reclaman el del termómetro. Apagarlo en Testing anda, como prueba a mano: la fila dice "termómetro · se vuelve a encender solo" y la vuelta siguiente de `watchLoop` lo enciende.

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
- Cadencia por UVC: libuvc recorre los intervalos que declara la cámara para ese tamaño en el orden del descriptor y se queda con el primero cuya cadencia cae dentro del rango pedido (ambos extremos incluidos); si ninguno cae, no abre. Con un rango de 1 a 30 entran los 30 y los 5 cuadros, y gana el que la cámara declare primero. Por eso la app pide entre 29 y 30 (no 30 exactos: libuvc calcula la cadencia con división entera y un intervalo declarado como 333334 da 29), y el rango de 1 a 30 sólo si la cámara no los ofrece. libuvc no dice qué cadencia negoció: la tarjeta del Panel Local muestra los cuadros por segundo que llegan.
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

### Auricular USB de respaldo (antes el Logitech H390)

- Desde el plan 13 el armado no lleva H390: los auriculares van al jack de la tablet y el hub no tiene puerto para un auricular. La lógica queda como respaldo inofensivo.
- Si hay un auricular USB enchufado, la app lo elige por nombre: "H390" y, si no lo encuentra, "Logitech" (configurable en **Nombre del auricular USB**). El H390 era USB Audio Class, VID `046D` (PID probable `0A8F`).
- Su micrófono nunca se usa como estetoscopio aunque su nombre coincida.
- El estetoscopio y la videollamada (con Auriculares) salen por él sólo cuando no hay nada enchufado en el jack. El estetoscopio nunca va al parlante de la tablet, que se acopla; la videollamada sí puede ir al parlante si se lo elige en Configuración → Kiosco, y es lo último con Auriculares.

### Auriculares por el jack de la tablet

- Auriculares con cable en la ficha de 3,5 mm de la tablet. Android los lista como `TYPE_WIRED_HEADSET` (con micrófono) o `TYPE_WIRED_HEADPHONES` (sin micrófono), y **sólo mientras están enchufados**. Testing → Dispositivos que ve Android los muestra como "auricular(es) con cable (jack)".
- No llevan nombre en la configuración: el tipo alcanza, y son de la tablet, así que no se confunden con el adaptador ni con un auricular USB.
- Son los auriculares del armado y tienen prioridad sobre un auricular USB para el estetoscopio (la escucha y las grabaciones) y para la videollamada con Auriculares. La salida del estetoscopio se elige al tocar Escuchar: enchufarlos o desenchufarlos en plena escucha no la cambia.
- Su micrófono nunca hace de estetoscopio: el estetoscopio sólo se toma de una entrada USB.
- Sin ficha de 3,5 mm, un adaptador USB-C a jack aparece como auricular USB con su propio nombre: ese nombre va en **Nombre del auricular USB**.

## Permisos USB

| Equipo | En `usb_device_filter.xml` | Por qué |
|---|---|---|
| Plaqueta PM6750 | Sí (fabricante `0483`) | La app abre el puerto serie. |
| Termómetro | Sí (por fabricante de chip serie) | La app abre el puerto serie. |
| Hub YKUSH3 | Sí (fabricante `04D8`) | La app manda órdenes HID. |
| Otoscopio | Sí (`349C:3301`) | El camino UVC abre el dispositivo directo. |
| Adaptador de audio | No | Android lo maneja como audio; pedir permiso sólo pondría un diálogo de más arriba de Chrome. |
| Auricular USB de respaldo | No | Ídem. |

Con "Usar de forma predeterminada" marcado, Android da el permiso solo cada vez que el equipo aparece, también después de un reinicio. Sin la casilla, el permiso que da `requestPermission` vale para ese `deviceId` y se pierde al apagar el puerto. Con el kiosco adelante el puerto del otoscopio queda encendido, así que alcanza con aceptarlo una vez marcando la casilla; en la instalación conviene encender su puerto desde Testing para que aparezca el pedido (lo recuerda el paso Kiosco).

Para el otoscopio hay un solo pedido, el de Android por el filtro (`USB_DEVICE_ATTACHED`), con la casilla (plan 12, parte 2). Antes, 3 s después, `UsbHub.requestMissingPermissions` sumaba otro con `requestPermission`, sin casilla, y quedaban los dos a la vez: resuelto, ese pedido automático ya no incluye al otoscopio (sí la plaqueta, el termómetro y el hub). Para volver a pedirlo a propósito (**Pedir permiso USB**, el Panel Local y la teleconsulta), `OtoscopePermission` decide: con YKUSH3 se apaga el puerto del otoscopio 2 s y se vuelve a encender, y Android muestra de nuevo el pedido con la casilla (`[USB] Permiso del otoscopio: se apaga y enciende el puerto N …`); sin hub, o si el hub no contesta, `requestPermission` sin casilla. Con permiso nunca se le apaga el puerto: se mira justo antes de apagar.

## Cómo se elige la salida de la llamada

Lo que sigue es lo esperado por razonamiento: no se probó en la tablet ni en el emulador, que no tiene hub ni audio USB. Que los puertos queden así está en el código (`HubRestState`); que Android mande los medios a la salida USB que haya es comportamiento de Android, y en el código de Berry Med no hay nada que lo fuerce.

Desde el plan 13 la salida elegida no cambia ningún puerto: en reposo, 1 y 2 apagados y 3 (el termómetro, que no es audio) encendido; con el kiosco, los tres encendidos.

| Salida elegida | Salidas USB en reposo | Qué se espera en `MODE_NORMAL` |
|---|---|---|
| Parlante | Ninguna | Los medios van al parlante. |
| Auriculares, con auriculares en el jack de la tablet | Ninguna | Los medios van al jack. En modo comunicación, Chromium también los prefiere y `CallAudioRouter` los fija. Es lo buscado. |
| Auriculares, sin nada en el jack | Ninguna | Los medios van al parlante (o a un auricular USB de respaldo, si hubiera uno). |

- Por eso, en `MODE_NORMAL` el selector debería seguir andando **hasta que se encienda el puerto del estetoscopio**: el adaptador sí tiene salida y puede llevarse los medios de la llamada. Ése es el riesgo principal.
- `setCommunicationDevice` sólo agrega la distinción entre un auricular USB y el adaptador, y sólo con modo comunicación. Con reproducción de medios no mueve el audio.
- Con el kiosco adelante (plan 12) el adaptador del estetoscopio y el micrófono del otoscopio están encendidos durante toda la llamada: el adaptador está siempre, no sólo al publicar. Ver los riesgos.
- Vale para el hub en reposo. Antes de aplicarse (de fábrica la placa arranca con los tres encendidos, salvo que se haya guardado otro arranque) o con un puerto que quedó encendido porque no se pudo leer, hay más de una salida USB.

## Riesgos a confirmar en la tablet real

El emulador no los muestra. Se revisan con Testing → "Dispositivos que ve Android" y con `adb logcat -s BerryMed` (ver "Probar el Panel Local en el tótem" en el `README.md`).

| Riesgo | Qué mirar |
|---|---|
| Si el otoscopio aparece como cámara externa (Camera2) o sólo como USB (UVC) | En Testing, Cámaras: "externa (USB)". En el panel, el encabezado del otoscopio dice el camino. |
| Con qué nombre aparece el adaptador de audio USB | En Testing, los dispositivos de audio: tiene que estar el adaptador, y los auriculares del jack mientras están enchufados. Anotar el nombre para la configuración. |
| Ancho de banda isócrono con otoscopio y audio a la vez en el mismo hub, con el termómetro también en el hub | Encender el otoscopio y escuchar el estetoscopio al mismo tiempo, y medir con el termómetro: que no se corte la imagen, el sonido, la plaqueta ni la lectura. |
| La cadencia del otoscopio, sola y con el estetoscopio escuchando (plan 9) | El encabezado del otoscopio en el panel ("En vivo · UVC · 30 cuadros/s") y la línea `[OTOSCOPIO] Imagen por UVC a 800×800 MJPEG, 30 cuadros/s`. Después, escuchar el estetoscopio al mismo tiempo y ver si baja. |
| Que `setPreferredDevice` realmente enrute al adaptador y a los auriculares del jack | Que el sonido salga por el jack y no por el parlante, y que la onda sea la del estetoscopio y no la del micrófono del auricular. |
| Permiso USB del otoscopio negado sin diálogo (Android 10 o más) si la app todavía no tiene el permiso de cámara | Aceptar el permiso de cámara en el Panel Local antes del permiso USB del otoscopio. |
| El otoscopio por UVC sin vista para la teleconsulta (libuvc entrega cuadros sin superficie donde dibujar) | Testing → Teleconsulta: "otoscopio por UVC" con cuadros por segundo mayores a 0, y el médico ve la imagen. |
| Camera2 para la teleconsulta mientras Zoom usa la cámara de la tablet (límite de cámaras abiertas a la vez) | Por eso se prefiere UVC; si la tablet lo lista por Camera2, que el médico vea el otoscopio y el paciente siga viéndose en Zoom. En el emulador, con una sola cámara, abrir el otoscopio le sacó la cámara al `WebView`. |
| Que el adaptador ofrezca `UNPROCESSED` | La línea `[TELE] Estetoscopio por …` dice la fuente. |
| Que Android no silencie la captura del estetoscopio con Zoom en la llamada dentro del kiosco | Testing → Teleconsulta: "la captura no está silenciada". |
| **Riesgo principal.** Que el puerto del estetoscopio, al encenderse en plena llamada en `MODE_NORMAL`, se lleve la salida de medios del `WebView`. El selector ahí depende de los puertos (ver [Cómo se elige la salida de la llamada](#cómo-se-elige-la-salida-de-la-llamada)) y el adaptador sí tiene salida | Publicar el estetoscopio durante la llamada, con Parlante y con Auricular: que se siga oyendo al médico por la misma salida y no por el jack del adaptador. |
| Que el `WebView` entre en modo comunicación con Zoom. En el emulador (WebView 124, Android 15) una llamada WebRTC de prueba quedó en `MODE_NORMAL`, captura `CAMCORDER` y reproducción `USAGE_MEDIA`, y `CallAudioRouter` no actuó: el dispositivo lo fijó Chromium | Testing → Teleconsulta: "modo comunicación" y una línea `[LLAMADA]` durante la llamada. Con `adb shell dumpsys audio`, `Actual mode` y el `usage` del reproductor del `WebView`. |
| Que en `MODE_NORMAL` el audio de medios siga la salida USB como se espera | Con Parlante elegido y los puertos en reposo, ¿por dónde se oye al médico? Con Auriculares, que salga por el jack. Qué micrófono toma la llamada en cada caso. |
| Que `setCommunicationDevice` acepte los auriculares del jack de la tablet y que el micrófono acompañe | Con auriculares en el jack y Auriculares elegido, Testing → Teleconsulta tiene que decir "el jack de la tablet" durante una llamada, sin una línea "Android no aceptó". Que el médico oiga al paciente por el micrófono del auricular con cable si tiene, si no por el de la tablet. Al desenchufarlos, el parlante. |
| Que el estetoscopio suene por el jack de la tablet | Con auriculares en el jack, Escuchar: la línea `[ESTETO] Escuchando por …` dice "(jack de la tablet)". Reproducir una grabación también. Al desenchufarlos y volver a Escuchar, no suena y el panel lo dice. |
| Que el micrófono del otoscopio no coincida con el nombre del estetoscopio | En Testing, los dispositivos de audio: el nombre configurado (`USB Audio`) tiene que identificar sólo al adaptador. |
| **Plan 12.** Con el kiosco adelante, el adaptador del estetoscopio (entrada mono, salida estéreo) y el micrófono del otoscopio (USB Audio mono 32 kHz) quedan presentes durante toda la videollamada. `CallAudioRouter` fija la salida, y el micrófono acompaña al dispositivo de comunicación sólo en modo comunicación; no puede excluir entradas: en `MODE_NORMAL` Chromium graba de la entrada que le da Android | Testing → Teleconsulta, "Entradas grabando": la llamada (`VOICE_COMMUNICATION` o `CAMCORDER`) tiene que grabar del jack o del micrófono de la tablet, nunca de `USB Audio` ni del otoscopio. Que el médico oiga al paciente por el micrófono del auricular. |
| Que con el kiosco adelante los puertos queden 1, 2 y 3 encendidos | La línea `[YKUSH] Puertos con el kiosco: puerto 1 encendido, puerto 2 encendido, puerto 3 encendido` en `adb logcat -s BerryMed` y las luces del hub. Testing → Hub YKUSH3 → Leer estado sólo sirve en el primer segundo: abrir Testing saca al kiosco de adelante y el servicio aplica el reposo. |
| **Plan 13.** Que el puerto 3 quede encendido siempre y el termómetro mida en el Panel Local y en la videollamada | La luz del puerto 3 encendida con el kiosco, la configuración, Testing y el Panel Local. En el Panel Local, apretar el gatillo y ver la lectura; en una consulta, que la temperatura llegue a OnDoctor. Apagar el puerto 3 en Testing: tiene que volver a encenderse en un segundo, con una línea `[YKUSH] Puerto 3 del termómetro encendido`. |
| Consumo: con el kiosco, tres puertos encendidos todo el tiempo (otoscopio con su luz, adaptador y termómetro); el termómetro también en reposo | El hub va con su fuente de 5 V, así que no carga la tablet; mirar que el otoscopio no caliente con horas encendido. |
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
  +-- Hub YKUSH3 ............. HID 04D8:F11B
        |
        +-- Puerto 1: Otoscopio ............. UVC 349C:3301, MJPEG 800x800
        |
        +-- Puerto 2: Adaptador audio TTGK .. 3302:29E3, "USB Audio"
        |                 |
        |                 +-- jack 3,5 mm TRRS: Estetoscopio Dalia StethoAI
        |
        +-- Puerto 3: Termómetro IR ......... serie USB, manda sólo al apretar el gatillo; siempre encendido

Tablet Android, jack 3,5 mm: auriculares con cable, salida del estetoscopio y de la llamada
```
