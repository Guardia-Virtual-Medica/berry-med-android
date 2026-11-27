# Decisiones del proyecto

Decisiones de producto y de alcance de Berry Med, en orden cronológico. El detalle de cada etapa está en `docs/planes/`, el armado en `docs/hardware.md` y la interfaz del Panel Local en `docs/redisenio/design_handoff_panel_medico/`.

## 28 de octubre de 2026

### Panel Local: alcance

- El Panel Local es para atención **presencial, sin teleconsulta**: el médico o el enfermero está al lado del paciente.
- No identifica al paciente.
- Es exclusivo con Chrome: una cosa o la otra. Tiene un ícono propio en la tablet, además del botón en la pantalla principal.
- No se guarda nada en la v1. Para una v2 queda preguntar si guardar y generar un PDF local. Nunca se envía nada a OnDoctor desde el panel.
- PIN de acceso `1515`, configurable; se pide al abrir y cada vez que el panel vuelve al frente.
- ECG con las 7 derivaciones, estilo papel con cuadrícula. Sólo visualización, sin alarmas.
- Las ondas se dibujan a mano con `Canvas`: las librerías de gráficos no llegan a 250 muestras por segundo por derivación (plan 1).

### Hardware

- Cámara: la de la tablet. Otoscopio por el hub USB YKUSH3. Estetoscopio por jack, a través de un adaptador de audio USB. Auricular USB Logitech H390. Tablet Android de última versión. Tal vez un ecógrafo en el futuro.
- El relé que cortaba los 12 V de la plaqueta ya no va: se corrigió el firmware.
- El estetoscopio suena sólo por el auricular; por el parlante de la tablet se acopla. El adaptador y el auricular se eligen por nombre, configurable, y el micrófono del auricular nunca hace de estetoscopio (plan 2).
- El otoscopio se pide a 800×800: a 1440² se come el ancho de banda del USB 2.0 que comparte con la plaqueta (plan 3).

### Forma de trabajo

- Los planes los escribe un modelo (Fable) y los ejecuta un agente Opus 5.5 con effort medio. Cada etapa es un PR que se revisa antes de mergear.

## 29 de octubre de 2026

### Estetoscopio y otoscopio

- Los planes 2 y 3 se arman completos sin esperar la prueba de hardware.
- El otoscopio tiene los dos caminos de video (Camera2/CameraX y UVC con libuvc) y elige solo según lo que vea la tablet; el panel dice cuál usó.
- El panel sólo apaga los puertos del YKUSH3 que encendió él. Un puerto que ya estaba encendido queda encendido al salir, para la teleconsulta. Si el hub no contestó la lectura del estado, enciende igual pero no apaga al salir.
- Otoscopio ampliado al centro de la pantalla con el resto desenfocado (plan 4, PR #12). Se eliminó después en el rediseño.

### LiveKit

- Hoy ningún programa del tótem publica el otoscopio ni el estetoscopio a la sala de LiveKit: la API emite tokens y la app del médico se suscribe, pero el kiosco no publica.
- Se decidió **no cambiar nada** por ahora y hacer foco en el Panel Local. La publicación desde Berry Med queda como plan 6, después.

### Envío de mediciones

- El tope de 30 minutos de la consulta no es necesario: se eliminó (PR #13). La consulta termina con `stop-monitoring` o con tres `404` seguidos.

### Rediseño del Panel Local (plan 5)

- Rediseño completo según el handoff de diseño hi-fi de `docs/redisenio/`, en tres etapas: 5a (PR #14), 5b (PR #15) y 5c (PR #16).
- Se aparta del handoff en:
  - Nada se guarda en disco: capturas y grabaciones viven sólo en memoria y se descartan al finalizar o al bloquearse el panel.
  - Sin alarmas: ni límites de FC ni colores de alarma.
  - Termómetro sin botones Conectar ni Medir: manda solo al apretar el gatillo.
  - ECG en milímetros reales, no "4 dp = 1 mm".
  - El PIN se mantiene.
  - Abrir un dispositivo no lo enciende y cerrarlo no lo apaga.
  - Se eliminó el otoscopio ampliado del plan 4: el dispositivo abierto ya es grande.
- Implementación de 5a:
  - Los cuatro paneles (ECG, otoscopio, estetoscopio, cámara) quedan superpuestos y el abierto pasa al frente; no se ocultan con `GONE`, para no cortar el video del otoscopio.
  - Ya no se muestran las ondas de SpO₂ y respiración ni la temperatura de la sonda de la plaqueta.
  - Bajo la FC va "Electrodos sueltos" o la arritmia que informa la plaqueta.
- Implementación de 5b: capturas en dos pasos (Congelar, después Guardar captura o Descartar y volver), las últimas cuatro en miniatura.
- Implementación de 5c:
  - Filtros Corazón (20 a 250 Hz), Pulmón (100 a 1000 Hz) y Amplio (20 a 1000 Hz); volumen de 0 a 10; grabación de 15 s; las últimas tres grabaciones se reproducen por el auricular.
  - La lista de grabaciones va debajo del fonocardiograma, por falta de alto.
  - El filtro queda fijo mientras graba.

### LiveKit: otoscopio y estetoscopio en la teleconsulta (plan 6)

- Reemplaza la decisión de más arriba ("no cambiar nada"): el médico de OnDoctor ve el otoscopio y escucha el estetoscopio del tótem durante la teleconsulta. La videollamada sigue en Zoom; los instrumentos van por la sala de LiveKit del encuentro. Plan en `docs/planes/plan-6-livekit-teleconsulta.md`, en tres partes: API (token para publicar), kiosco (suelta el micrófono de Zoom mientras suena el estetoscopio) y Berry Med (publica).
- Berry Med escucha `start_oth`, `stop_oth`, `start_steth` y `stop_steth` en el canal del tótem, pide el token a `POST {URL base}/{tótem}/livekit-token` y publica una pista a la vez. Con el Panel Local abierto las órdenes se ignoran.
- SDK `io.livekit:livekit-android` 2.29.0 (la última 2.x con más de dos semanas). Sin manejador de audio (`NoAudioHandler`), tipo de audio de medios y módulo de audio propio: Berry Med nunca toca el modo, el foco ni el ruteo del audio, que son de Zoom.
- Estetoscopio: fuente `UNPROCESSED` (o `MIC`), sin procesamiento de voz, filtro Amplio y volumen por defecto aplicados al buffer de captura, Opus a 64 kbps sin DTX.
- Otoscopio: el mismo camino que elige el Panel Local (UVC o Camera2), sin vista, 800×800 hasta 30 cuadros, 1,5 Mbps, sin simulcast, manteniendo la resolución.
- Servicio en primer plano: `connectedDevice` desde el arranque; suma `camera|microphone` sólo con una pantalla de Berry Med visible, y nunca baja. Al encender la tablet, si la app tiene "Mostrar sobre otras apps", abre la pantalla principal un momento y la manda atrás; si no, la notificación pide abrir Berry Med una vez.
- El APK sólo lleva ARM (`arm64-v8a`, `armeabi-v7a`) y las librerías nativas comprimidas: WebRTC trae cuatro arquitecturas y el APK pasaba de 12 a 70 MB; queda en 25 MB.
- No se cambia la app del médico. Nota: `useLiveKitDevices.ts` de `gvm.mt.doctors` espeja el video (`scaleX(-1)`) e invierte la imagen del otoscopio; es una línea si se decide sacarlo.

### Plan 6, revisión: el kiosco dentro de Berry Med

- El usuario decidió **no modificar la API, el kiosco ni la app del médico**. Se cierran gvm.mt.api#29 y gvm.mt.totem#70. Revisión en `docs/planes/plan-6-revision-kiosco-en-berry-med.md`.
- El kiosco corre dentro de Berry Med, en un `WebView`, como hacía la app del proveedor (se deduce del historial de los repos: el kiosco navega con `window.location.href` a una dirección con `serverUrl` y `token`, y antes la API mandaba `koplak://auth?…`). Berry Med es la app de inicio de la tablet; se sale a la configuración con cinco toques en una esquina y el PIN.
- El token sale de esa dirección: Berry Med entra a la sala apenas lo ve y se queda; `start_*`/`stop_*` sólo publican y despublican; `stop-monitoring` sólo despublica. Sale con un token nuevo, cuando el kiosco vuelve al inicio, al login o al setup, o a las 3 horas. El token nunca va al log.
- `serverUrl` es el subdominio de LiveKit Cloud: sin esquema, el servidor es `wss://{serverUrl}.livekit.cloud`.
- Con el kiosco y el estetoscopio en la misma app, Android no silencia la captura del estetoscopio con Zoom en la llamada: ya no hace falta pausar el micrófono del kiosco.
- Se sacan del PR #19 el pedido del token a la API, el permiso "Mostrar sobre otras apps" y la apertura de la pantalla principal desde el arranque.

### Audio de la videollamada y puertos del YKUSH3 (plan 7)

- La videollamada se escucha por defecto en el H390; en el paso Kiosco se cambia por el parlante. El micrófono acompaña a la salida. No alcanza al estetoscopio del Panel Local, que sale sólo por el auricular. Plan en `docs/planes/plan-7-audio-llamada-y-puertos.md`.
- Si la salida elegida es el auricular y no está, la llamada sale por el parlante, con línea en el log y en Testing. Nunca el adaptador del estetoscopio, aunque sea el único USB.
- `CallAudioRouter` fija y reafirma el dispositivo de comunicación con la llamada en `MODE_IN_COMMUNICATION`: como mucho una vez por segundo y cinco intentos seguidos. Los intentos se perdonan sólo si el último duró 10 s: en un ciclo con Chromium el dispositivo correcto vuelve siempre. Corrige lo de "Berry Med nunca toca el ruteo": el modo y el foco siguen sin tocarse.
- Hallazgo al verificar: en el emulador una llamada WebRTC en el `WebView` queda en `MODE_NORMAL` y reproduce como `USAGE_MEDIA`, así que el router no actúa. Es una duda abierta para la tablet con Zoom, no un dato sobre Zoom. No se agregó otra forma de detectar la llamada: con esa reproducción `setCommunicationDevice` tampoco movería el audio.
- El selector no depende sólo del modo comunicación: los puertos también eligen la salida. Con Parlante, los puertos 2 y 3 quedan apagados en reposo y no hay salida USB; con Auricular, el H390 es la única. Por razonamiento, sin probar en la tablet, en `MODE_NORMAL` debería seguir andando hasta que se encienda el puerto del estetoscopio, cuyo adaptador puede llevarse los medios de la llamada: es el riesgo principal (`docs/hardware.md`). `setCommunicationDevice` sólo agrega la distinción entre el H390 y el adaptador con modo comunicación.
- Lo que Berry Med fijó se suelta una vez con `clearCommunicationDevice` al salir del modo comunicación, como pide Android.
- El permiso USB del otoscopio ya no sale solo en la instalación, porque su puerto descansa apagado: es un ítem de la lista del paso Kiosco, con botón a Testing y sin estado, porque Android no dice si quedó "Usar de forma predeterminada".
- Puertos en reposo: otoscopio y estetoscopio apagados, auricular según la salida. Cambia la regla "el servicio no toca los puertos solo". Si alguien usa un equipo se espera a que se libere y se aplica entonces; un hub que no contesta no se reintenta hasta que vuelva a conectarse o cambie la salida.
- Protocolo según la hoja de datos: sin `0x2A`, con el comando repetido en el segundo byte. `0x41` es persistente y no se puede leer: sólo con el botón de Testing.

### Video del médico en negro dentro del `WebView` (1.3.1)

- En la tablet, con la 1.3.0, la videollamada mandaba el video del paciente pero no mostraba el del médico; en Chrome andaba.
- Causa: Berry Med sacaba el "; wv" del user agent pero dejaba "Version/4.0". Con eso Zoom Video SDK (2.3.15) toma al `WebView` por el navegador viejo de Android, avisa "please use Chrome", dice que no admite varios videos y dibuja el video remoto por un camino de respaldo.
- Arreglo: `KioskUserAgent` saca también el "Version/x.x". Zoom lo toma por Chrome y muestra el video del médico con un elemento `<video>`, el mismo camino que usa en Chrome.
- Verificado en el emulador con una sesión de Zoom de prueba (médico en Chrome con cámara falsa, paciente en Berry Med): antes, aviso y camino de respaldo; después, sin aviso y el mismo camino que Chrome, con video en las dos direcciones. En el emulador el respaldo igual se veía: que en la tablet quede resuelto se confirma allá.

### El kiosco se recarga solo (plan 8, 1.3.2)

- Reporte de campo: la maleta prendió, Berry Med abrió el kiosco antes de que la tablet se conectara al wifi y el `WebView` quedó en "Webpage not available" (`net::ERR_INTERNET_DISCONNECTED`). Para destrabarlo hubo que entrar a la configuración, cambiar la URL, guardar y volver a ponerla.
- Causa: `KioskActivity.show()` cargaba sólo cuando la URL era distinta de la cargada, así que nada volvía a intentar.
- Reintento automático: si falla el documento principal (error de conexión o HTTP 5xx; los recursos secundarios no cuentan), Berry Med tapa el `WebView` con su propia pantalla y un botón **Reintentar**. Reintenta solo apenas Android avisa que hay red, cada 10 s y al volver al kiosco. Reintentar es `reload()`: vuelve a pedir la dirección que falló, no la del inicio.
- Recarga a pedido: el diálogo de los cinco toques suma **Recargar**, que también pide el PIN (un paciente no tiene que poder reiniciar el kiosco en plena consulta) y carga la URL configurada desde cero. Permiso nuevo `ACCESS_NETWORK_STATE`, normal y sin diálogo.

### El otoscopio a 30 cuadros (plan 9, 1.3.3)

- Reporte de campo con la 1.3.2: en el Panel Local la imagen del otoscopio sale bastante trabada. Plan en `docs/planes/plan-9-otoscopio-30-cuadros.md`.
- Causa más probable: el otoscopio ofrece cada tamaño a 30 o a 5 cuadros y la app le pedía a libuvc un rango de 1 a 30. libuvc se queda con el primer intervalo del descriptor que cae en el rango, así que si la cámara declara primero el de 5, abre a 5.
- Por UVC se piden entre 29 y 30 cuadros (no 30 exactos: libuvc calcula la cadencia con división entera y un intervalo declarado como 333334 da 29; el de 5 sigue afuera); sólo si la cámara no los ofrece para ese tamaño se pide el rango de 1 a 30 y, si tampoco, sin rango, como antes. El log dice la cadencia pedida (`a 800×800 MJPEG, 30 cuadros/s` o `cadencia a elección de la cámara`).
- Por Camera2 (el camino que la tablet real no usa) se pide `CONTROL_AE_TARGET_FPS_RANGE` en 30 fijo si la cámara lo ofrece; si no, el rango que llegue a 30 con el mínimo más alto; si ninguno llega, no se pide nada.
- La tarjeta muestra los cuadros por segundo que llegan de verdad ("En vivo · UVC · 29 cuadros/s"), para que el próximo reporte traiga el número: si dice 30 y se ve trabada, el problema está en otro lado. Por UVC se cuentan los cuadros que dibuja la vista; por Camera2, las capturas que completa la cámara (`PreviewView` no avisa cuándo dibuja). Al log va una línea sólo si la cadencia queda por debajo de 20 más de 5 s seguidos.
- No se cambia la resolución (800×800) ni el ancho de banda de libuvc. Testing → Teleconsulta sigue mostrando la cadencia de la publicación como antes.

### El estetoscopio y la videollamada por el jack de la tablet (plan 10, 1.3.3)

- Pedido: que el sonido salga por defecto por el jack analógico de la tablet, no por el H390. Plan en `docs/planes/plan-10-estetoscopio-jack-tablet.md`.
- Regla automática, sin opción nueva: si hay algo en el jack de la tablet, el estetoscopio suena por ahí; si no, por el H390. Nunca por el parlante (se acopla) ni por el adaptador. `AudioPick.stethoOutput`.
- La salida del estetoscopio se elige al tocar Escuchar o al reproducir una grabación: enchufar o desenchufar en plena escucha no la cambia. Se dice en el KDoc, no en pantalla.
- El micrófono del auricular del jack nunca hace de estetoscopio: el estetoscopio sólo se toma de entradas USB, con test.
- Los puertos del YKUSH3 no cambian: Escuchar sigue encendiendo el del adaptador y el del H390. Con el jack puesto la salida ya está y la espera de hasta 5 s es sólo por el adaptador.
- La videollamada también: con **Auriculares** sale por el jack, si no por el H390 y, si tampoco, por el parlante. **Parlante de la tablet** sigue mandando sólo la llamada al parlante. El valor guardado `auricular` no cambia y pasa a querer decir "auriculares, jack primero", así la configuración existente sigue valiendo. `HubRestState` no cambia: con Auriculares el H390 queda encendido en reposo como respaldo.
- Chromium en modo comunicación ya prefiere el auricular con cable antes que lo USB: `CallAudioRouter` lo confirma y lo reafirma, y lo nombra "el jack de la tablet" porque esos dispositivos suelen venir sin nombre.
- Con un adaptador USB-C a jack no hace falta nada nuevo: Android lo lista como auricular USB y se configura su nombre en **Nombre del auricular**.

### Ícono y botón ONDOCTOR365 (plan 11, 1.3.4)

- El usuario pidió un botón OnDoctor que lleve directo al kiosco. Se hace con el kiosco que ya corre dentro de Berry Med, no con Chrome: Chrome en Android no tiene modo kiosco (muestra la barra de direcciones y el paciente puede irse; bloquearlo exige hacer a Berry Med "device owner", con reset de fábrica), Berry Med dejaría de ver la URL con `serverUrl`/`token` de LiveKit (el médico no recibiría otoscopio ni estetoscopio) y Android volvería a silenciar el estetoscopio con Zoom en el micrófono, porque ya no serían la misma app. Plan en `docs/planes/plan-11-icono-ondoctor.md`.
- Tercer ícono, **ONDOCTOR365**: un `<activity-alias>` de `KioskActivity` con fondo violeta `#5B3FA8` y el mismo dibujo. Alias y no otra pantalla: el kiosco es uno solo, con su `WebView`, su sala y su recarga. El filtro HOME queda en `KioskActivity`. Sin PIN para entrar (es la pantalla del paciente); el PIN sigue para salir.
- El kiosco tiene su propia tarea (`taskAffinity` `com.guardiavirtual.berrymed.kiosk`), como el Panel Local: así el ícono de Berry Med muestra la configuración y no el kiosco, y abrir la configuración (`singleTask`) no destruye el kiosco que estaba arriba. Verificado en el emulador: el botón de inicio, el ícono ONDOCTOR365 y el botón de la pantalla principal vuelven a la misma instancia, sin recargar.
- Visto al verificar, y ya pasaba antes de este cambio (probado con `main`): si el kiosco se abre primero con el botón y no como app de inicio, el botón de inicio crea un segundo kiosco en la tarea de inicio de Android. En la tablet no pasa porque el kiosco arranca siempre como app de inicio; queda anotado.
- Pantalla principal: título "Maletín de atención ONDOCTOR365", **Evitar que se duerma** pasa a una ruedita arriba a la derecha y la fila queda con cuatro botones rellenos de 56 dp (USB-config, Testing, Panel Local y ONDOCTOR365 en violeta). En 1280×800 dp los cuatro textos entran enteros.

### La pantalla principal con el estilo del Panel Local (plan 11, parte 2, 1.3.4)

- El usuario vio la parte 1 en el emulador y pidió que la configuración no esté siempre a la vista y que la pantalla principal se vea como el Panel Local, "así no parecen dos apps distintas".
- La configuración pasa a una capa que tapa el contenido de abajo de la cabecera; se abre con **USB-config** (el nombre lo eligió el usuario) y se cierra con ← o atrás, sin guardar. Se abre sola sin identificador del tótem y con `MainActivity.EXTRA_OPEN_CONFIG` desde el kiosco sin URL. El PIN del kiosco lleva a la pantalla principal con la capa cerrada, salvo que falte la URL del kiosco.
- **Pedir permiso USB** se muda al menú de la ruedita, arriba de **Evitar que se duerma**. La ayuda de «Usar de forma predeterminada» va al paso Plaqueta, junto a los equipos USB.
- Todo con los tokens del Panel Local (`Theme.BerryMed.Panel`, Barlow, `Panel.Card`, `Panel.Chip`, `Panel.Button.Secondary`); la capa usa `ThemeOverlay.BerryMed.FormDark` y el error en `test_fail`. Los chips de la cabecera usan los mismos colores que los del panel; el del termómetro va ámbar mientras está conectado sin mediciones (`TotemState` suma `thermoConnected` y `thermoMeasured`).

### Un solo estilo de botón (plan 11, parte 3, 1.3.4)

- Cuadrado se toca, redondo informa: todos los botones del Panel Local y de la pantalla principal tienen la misma forma (esquinas de 6 dp, Barlow SemiBold 16 sp, borde de 1 dp) y cambian sólo de color y de alto; los chips de estado, los badges y el toast quedan redondos. Los segmentos pasan a 6 dp.
- **Finalizar** va en rojo (`Panel.Button.Danger`, `alarm`, `alarmPressed` apretado), en la cabecera y en el botón que confirma del diálogo, para que la acción se lea igual en los dos lugares. Se borran `Panel.Button.Light` y los selectores `panel_light_bg` y `panel_outline_bg`.

### Testing con el estilo del Panel Local (plan 11, parte 4, 1.3.4)

- Sólo estilo: tema Panel, cabecera con ← cuadrado, tarjetas `Panel.Card` con el título en el color de cada vital, la forma única de botón (Iniciar prueba y Publicar en azul) y los campos de LiveKit oscuros. El texto crudo sigue en monoespaciada, en `muted`.
- Se borran `card`, `monitor`, `vital_on`, `vital_dim`, `status_live` y `status_dot`; `Theme.BerryMed` pasa a los tokens del panel. `signal` queda: es el fondo del ícono de Berry Med.

### Con el kiosco adelante, otoscopio y estetoscopio encendidos (plan 12, 1.3.5)

- Reporte de campo con la 1.3.4: en la videollamada el otoscopio no transmitía y aparecía un pedido de permiso. La teleconsulta encendía el puerto, esperaba 8 s y, sin permiso, lo apagaba: el permiso, que vale para ese `deviceId`, se perdía, y la vez siguiente era otro dispositivo. El Panel Local dejaba el puerto encendido y por eso ahí se veía.
- Decisión del usuario: mientras `KioskActivity` está visible (entre `onStart` y `onStop`), los puertos del otoscopio y del estetoscopio quedan encendidos. Fuera del kiosco vale el reposo de antes. Reemplaza, para el kiosco, la regla del plan 7 que los dejaba apagados en reposo. Sin cambios en la API ni en el kiosco web. Plan en `docs/planes/plan-12-puertos-encendidos-en-kiosco.md`.
- Es sólo alimentación: la transmisión sigue empezando con `start_oth`/`start_steth` y al cortar el puerto queda encendido mientras siga el kiosco.
- `HubRestState.wanted(config, kiosk)` y `TotemRuntime.setKioskVisible`; el cambio lo aplica `watchLoop` en la vuelta siguiente, con la misma espera de `hubRestBlocked()`: nunca se apaga un equipo en plena llamada. El diálogo del PIN no cambia nada.
- La teleconsulta, como el Panel Local: con el otoscopio enchufado sin permiso no apaga el puerto, vuelve a pedirlo y dice "Falta aceptar el permiso USB del otoscopio en la tablet". La regla quedó en `OtoscopePower.afterTimeout`, la usan los dos. Si el puerto lo había encendido la teleconsulta, se apaga al salir de la sala. El estetoscopio no tiene esa espera: es audio y no pide permiso USB.
- **Guardar este arranque en el hub** guarda el estado del kiosco, porque la tablet arranca en el kiosco.
- El micrófono de la llamada no se puede excluir del adaptador ni del otoscopio desde `CallAudioRouter` (Android mueve la entrada con el dispositivo de comunicación y en modo normal la elige Chromium): Testing → Teleconsulta dice qué entradas están grabando. Riesgo anotado en `docs/hardware.md`.
- Un solo pedido de permiso para el otoscopio, el de Android con «Usar de forma predeterminada» (parte 2, decisión del usuario). `requestMissingPermissions` pedía además, a los 3 s, uno con `requestPermission` sin casilla: salían los dos casi juntos y, si se aceptaba el segundo, el permiso valía sólo hasta apagar el puerto. Ahora deja afuera al otoscopio; `permissionMissing()` lo sigue contando. Volver a pedirlo a propósito apaga y enciende su puerto, así Android vuelve a mostrar el pedido con la casilla; sin hub, el `requestPermission` de antes. La regla está en `OtoscopePermission`, con test. El ciclo va por el hilo del otoscopio, en orden con el Panel Local, y no toca el estado del kiosco ni `PanelPorts`: el puerto vuelve a quedar encendido.

## 3 de noviembre de 2026

### El termómetro en el puerto 3 del YKUSH3, siempre encendido (plan 13, 1.3.6)

- Armado nuevo del maletín, validado por el usuario con Mauricio el 3 de noviembre de 2026: tablet → dock USB-C; del dock, la plaqueta y el hub YKUSH3 con su fuente; en el hub, 1 otoscopio, 2 adaptador de audio con el estetoscopio, **3 termómetro IR**. El H390 ya no se usa: los auriculares van al jack de la tablet (plan 10). Sin cambios en la API ni en el kiosco web. Plan en `docs/planes/plan-13-termometro-en-puerto-3.md`.
- Sale `ykushHeadsetPort` y entra `ykushThermometerPort` (de fábrica 3, clave `ykush_thermometer_port`). Una configuración guardada sin la clave nueva carga el termómetro en el 3; la clave vieja `ykush_headset_port` se ignora al leer y se borra al guardar. El paso Plaqueta dice "Puerto del termómetro".
- El puerto del termómetro queda **siempre encendido**, con y sin kiosco y sin depender de la salida de audio: `HubRestState.wanted` lo da `true` en las dos ramas. La salida de audio de la llamada ya no cambia ningún puerto.
- Encender nunca espera: con `hubRestBlocked()` (consulta, Testing, Panel Local o teleconsulta usando un equipo), `HubRestState.changes(wanted, states, blocked, thermometerPort)` deja sólo el encendido del termómetro, una vez por hub (`Pending.thermometerDue`, también si el hub se reconecta). Las órdenes de apagar siguen esperando.
- Nadie apaga el termómetro: Escuchar enciende sólo el puerto del estetoscopio y `PanelPorts` del Panel Local y de la teleconsulta nunca reclaman ese puerto. **Apagar** en Testing anda como prueba a mano; la fila dice "termómetro · se vuelve a encender solo" y la vuelta siguiente de `watchLoop` lo enciende.
- **Guardar este arranque en el hub** guarda el termómetro encendido.
- Audio con la regla del plan 10: primero el jack de la tablet, después un auricular USB por nombre como respaldo inofensivo (la lógica no se borra, el texto ya no lo nombra como parte del armado) y, para la llamada, el parlante. El valor guardado de `callAudioOutput` no cambia.
- `ThermometerLink` y el envío no cambian: si el termómetro aparece tarde porque su puerto se encendió después, el link lo toma solo (busca cada 2 s) y `requestMissingPermissions` le pide el permiso USB.

### El otoscopio manda imagen en la videollamada (plan 14, 1.3.7)

- Reporte de campo con la 1.3.6: Testing → Teleconsulta decía "Sala conectada", "Publica: otoscopio por UVC" y **0.0 cuadros**; el médico recibía la pista sin imagen. En el Panel Local el otoscopio sí se veía. Plan en `docs/planes/plan-14-otoscopio-con-imagen-en-videollamada.md`.
- Causa: `UvcOtoscopeFrames` abría el otoscopio por libuvc sin superficie, con sólo el callback NV21. En libuvc 3.2.7 (`com.github.jiangdongguo.AndroidUSBCamera:libuvc`), `UVCPreview::startPreview` crea el hilo de captura sólo si hay ventana de preview (`UVCCamera::window does not exist/already running/could not create thread etc.` en `libUVCCamera.so`): sin superficie no se lee la cámara y el callback nunca corre. El Panel Local anda porque dibuja en un `TextureView`.
- Arreglo: antes de `startPreview`, `setPreviewDisplay(Surface)` (firma verificada con `javap` sobre el `.aar`) con la superficie de un `ImageReader` del tamaño abierto, en `PixelFormat.RGBX_8888` (libuvc dibuja la ventana con `uvc_any2rgbx`), `maxImages` 2, vaciado con `acquireLatestImage()?.close()` en un `HandlerThread` propio para que libuvc nunca se trabe esperando un buffer. Se eligió `ImageReader` y no un `SurfaceTexture` porque se consume sin contexto GL y el formato queda explícito; si el formato no le cuadrara, el primer error del vaciado queda en el log `[TELE]` y la alternativa es un `SurfaceTexture` desacoplado. Al soltar: sin callback, `stopPreview` y la cámara cerrada, y recién ahí el `ImageReader` y su hilo, sin excepciones que escapen.
- No se publica una pista vacía: `LiveKitBackend.publishOtoscope` espera el primer cuadro hasta 5 s (`FirstFrame`, con test) antes de `publishVideoTrack`, por los dos caminos. Si no llega, suelta todo y dice "El otoscopio abrió pero no manda imagen".
- Testing no se pelea con el reposo: con la pantalla Testing abierta (`onStart`…`onStop`), `hubRestBlocked()` es verdadero aunque la prueba no haya empezado; sólo se enciende el termómetro. Antes, un **Encender** a mano lo apagaba el reposo y el pedido de permiso del otoscopio aparecía y se iba. Al cerrar Testing, `hubRest.again()`.
- El paso Kiosco muestra el permiso USB del otoscopio: verde enchufado con permiso, ámbar enchufado sin permiso, gris desenchufado (con test en `KioskChecklistTest`).

## 14 de noviembre de 2026

### Nada de un paciente queda para el siguiente, y el PIN (plan 15, 1.3.8)

- Sale de la revisión del estado del proyecto sobre la 1.3.7: seis inconsistencias encontradas leyendo el código, ninguna por un reporte de campo. Regla que ordena las dos primeras: **una lectura pertenece a quien se la tomó**. Sin cambios en la API ni en el kiosco web. Plan en `docs/planes/plan-15-datos-entre-pacientes-y-pin.md`.
- **Termómetro.** La lectura y su instante van juntos (`ThermoSample`, bajo el lock de `ThermometerLink`): se guardan y se borran en el mismo paso, y sin lectura no hay edad. Se borra también al abrir y al cerrar el Panel Local (al cerrar, antes de soltar el modo, así la prueba que sale enseguida ya no la encuentra). Sin consulta, la prueba de cada 10 s lleva la lectura sólo si es reciente (`THERMO_FRESH_NANOS`, 60 s con el borde incluido; una "del futuro" no cuenta como reciente); la decisión está en `thermometerForPacket`, con test, y la usan `sendOnce` y la vista previa de Testing. Si esa prueba arranca la consulta (`resumeSession`), dentro de `sessionLock` se borra la lectura si no es reciente y se conserva si lo es. Con consulta no cambia nada.
- **Panel Local en blanco.** La presión se muestra sólo si se pidió con **Medir** en esta apertura (`localNibpAsked`, falso en `startLocal` y en `stopLocal`, verdadero cuando `localNibpStart` arranca); mientras no, la tarjeta queda vacía (`PressureCard.Empty`) y sin hora. El estetoscopio vuelve a Corazón y volumen 6 en `startLocal`, y la pantalla lo muestra al desbloquear. Al bloquear, el ECG vuelve a 25 mm/s, 10 mm/mV y siete derivaciones (`PanelUi.START_*`, los mismos con que se crea la pantalla). La cámara de la tablet queda como estaba.
- **Cuándo manda la plaqueta tramas de presión.** Según el código, sólo mientras mide: el arranque no abre ningún flujo de presión (`buildStartupCommands` no manda `0x02`, test `startupConfiguresBeforeOpeningStreamsAndSkipsNibp`); `0x02 0x01` es empezar a medir y `0x02 0x00` es STOP; y `BerryLink` da por terminada una medición porque el módulo "manda el estado dos veces por segundo mientras mide y se calla al terminar" (`nibpWentQuiet`, 30 s). En reposo no llegan tramas `0x03`. El comentario de `nibpResultAtMillis` dice que la plaqueta repite el resultado en cada trama de presión; `ProtocolTest` sólo muestra tramas de medición con la presión en cero. La regla del panel no depende de eso.
- **Testing con el panel abierto.** `testYkushSet` y `testNibpStop` devuelven "El Panel Local está abierto: …" y no hacen nada, como `startTesting`. **Leer estado** y **Guardar este arranque en el hub** siguen andando (no tocan los puertos). Hallazgo 15 de `docs/revisiones/revision-codigo-opus.md`.
- **PIN.** `checkPin` (junto a `pinAccepted`, con test) da aceptado, incorrecto o cargando; con la configuración sin leer (`TotemRuntime.configLoaded`, ahora legible desde las pantallas) nunca acepta. Panel y kiosco dicen "Cargando la configuración…" y dejan reintentar. `ConfigStore.flow` ya no emite `BerryConfig()` ante un error que no es de E/S: el runtime lo deja en la consola (`[CONFIG]`), sigue con la última configuración buena y reintenta cada 5 s. Hallazgo 18.
- **Aviso de la consulta.** Con el panel bloqueado y una consulta enviando, "Hay una consulta enviando: el Panel Local espera a que termine." se ve sin escribir nada y se va solo (lo refresca el reloj de la cabecera). Sin consulta no tapa un "PIN incorrecto". El control de verdad sigue en `startLocal`.
- **Kiosco.** Entrar y Recargar se asignan en `setOnShowListener` sobre los botones del `AlertDialog`: con el PIN mal o la configuración sin cargar el diálogo queda abierto, borra el campo y muestra el motivo como error del campo (`EditText.error`), sin cambiar el estilo del diálogo. Cancelar y tocar afuera cierran como antes.

### El panel lateral de estado (plan 16, 1.4.0)

- Una columna nativa a la derecha, en el kiosco y en el Panel Local, con el Wi-Fi, el modo y el botón para cambiarlo, los dispositivos y el sonido. Regla: **el panel muestra, no decide**; lee el estado que ya calcula la app y usa los caminos que ya existen (el PIN del Panel Local, `startLocal`/`stopLocal`, `callAudioOutput`). Plan en `docs/planes/plan-16-panel-lateral.md` y diseño en `docs/redisenio/design_handoff_panel_lateral/`.
- **Rojo para desconectado.** Un instrumento desconectado va en rojo (aro hueco y fila marcada) aunque el Panel Local no tenga alarmas: es algo para resolver antes de atender. Es la única excepción.
- **«Listo» en Local.** En el Panel Local, también en su pantalla de PIN, el otoscopio y el estetoscopio sin enchufar con el YKUSH3 presente dicen «Listo» en verde: sus puertos arrancan apagados y se encienden al abrirlos.
- **Sonido de toda la app, menos el estetoscopio.** La sección se llama «Sonido» y se ve igual en los dos modos. Sus opciones son «Auricular» y «Altavoz» (más genérico que «Tablet»). Altavoz mueve la videollamada al parlante y al micrófono de la tablet; el estetoscopio sigue sólo en el auricular, porque por el parlante se acopla (decidido con el usuario el 15 de noviembre de 2026).
- **Altavoz = `"parlante"`.** El segmentado de sonido escribe el mismo `callAudioOutput` de la configuración, sin PIN. Sin jack se ve Altavoz sin reescribir el ajuste. No se puede usar el micrófono de la tablet con el sonido en el auricular: queda para otro plan si hace falta.
- **No se abre solo** cuando algo pasa a rojo: correría el `WebView` en medio de una llamada. Abierto o cerrado vive en memoria (`TotemRuntime.statusPanelOpen`) y vuelve a abierto al reiniciar el proceso.
- **La ubicación para el SSID.** Sin el permiso de ubicación Android 10 en adelante esconde el nombre de la red. El kiosco la pide con la cámara y el micrófono (la precisa junto con la aproximada, como exige Android 12); si la niegan, el panel dice «Wi-Fi».
- Un solo PIN de Remoto a Local: el botón abre el Panel Local, que ya pide el suyo. Local a Remoto pasa por el mismo camino que Finalizar.

## 15 de noviembre de 2026

### Permisos de a uno y el Wi-Fi a mano (plan 17, 1.4.0)

- **Permisos USB de a uno.** En la tablet (rk3576, Android 14) se vio que, con tres pedidos juntos al arrancar, Android abre un solo `UsbPermissionActivity` y los otros dos se pierden sin respuesta. `PermissionRequests` deja un solo pedido en vuelo, en el orden plaqueta, hub YKUSH3 y termómetro (`UsbRole`). El siguiente sale con la respuesta (el broadcast trae `EXTRA_DEVICE`; sin él se libera igual, porque hay uno solo), a los **60 s** sin respuesta, o si el del pedido se desenchufa o ya tiene permiso. Un rechazado o vencido no se vuelve a pedir solo. **Pedir permiso USB** olvida todo y vuelve a pedir en fila, sin repetir el que está en vuelo. El otoscopio sigue fuera del pedido automático.
- **«Falta permiso» en ámbar.** Un equipo enchufado sin permiso ya no se ve igual que uno desenchufado: la fila dice «Falta permiso» en ámbar y se toca. «Sin permiso USB» del Hub USB (que se encendía por cualquier equipo) pasa a ser sólo del hub.
- **La fila de la plaqueta, el hub o el termómetro no cicla el otoscopio.** El plan decía usar lo mismo que **Pedir permiso USB**, pero eso también apaga y enciende el puerto del otoscopio si le falta el permiso, y su pedido de Android saldría a la par del de la fila: uno de los dos se perdería, que es justo lo que se arregla. Esas filas llaman a `requestUsbPermissions` (sólo la fila de USB); el otoscopio tiene su propia fila. El botón de la ruedita sigue como estaba.
- **Cámara en el otoscopio.** El panel no tiene fila para la cámara de la tablet: la falta del permiso de cámara se ve en la del otoscopio (después del permiso USB, que va primero). Micrófono en el estetoscopio, ubicación en el Wi-Fi.
- **Permisos de la app juntos al abrir** (`AppPermissions`, `AppPermissionAsker`), en la pantalla principal, el kiosco y el Panel Local, que conserva sus pedidos al usar cada cosa. La ubicación va precisa y aproximada juntas. Ir a los ajustes de la app sólo cuando el pedido salió de tocar una fila y Android ya no deja pedirlo; el pedido de al abrir nunca manda a los ajustes. La pantalla principal deja de pedir sus permisos de arranque por su cuenta; «Dar permisos» del paso Kiosco queda igual.
- **La fila del Wi-Fi sin ubicación pide la ubicación**, no el panel de Wi-Fi: el plan pide las dos cosas para esa fila. Sin red siempre abre el panel de Wi-Fi. Con la ubicación negada para siempre, tocarla abre los ajustes de la app hasta que se dé; el aviso «Sin conexión» y la fila sin red siguen abriendo el Wi-Fi.
- **En el riel** sólo hay ícono por grupo: el del Wi-Fi hace lo mismo que su fila; el de Dispositivos sigue abriendo el panel, donde se tocan las filas.
- **Aviso «Sin conexión»** (`NoWifiPrompt`): 5 s seguidos sin Wi-Fi, una vez por apertura de pantalla (`onStart`), se cierra solo al volver la red, no va arriba del PIN del Panel Local, de Finalizar, del PIN del kiosco ni del cambio de modo. Usa el diálogo de cambio de modo con «Ahora no» en lugar de Cancelar. El intent del panel de Wi-Fi está sólo en `WifiPanel`.
- **Instalación con `adb install -r -g`**: da los permisos de la app de entrada.
- **Berry Med como pantalla de inicio (punto 5).** El kiosco ya tenía `HOME` y `DEFAULT` en el manifiesto (plan 6): no hizo falta otra actividad. Lo nuevo es la decisión (`HomeTarget`, con test): con la URL del kiosco guardada, el kiosco; sin ella, la configuración (antes quedaba la pantalla «falta la URL» con un botón); sin la configuración leída, espera y reintenta al segundo. Para no quedar encerrado, la ruedita de la pantalla principal suma **Ajustes de Android**. Se elige como inicio una vez por tablet, con **Elegir app de inicio** del paso Kiosco o con `adb shell cmd package set-home-activity com.guardiavirtual.berrymed/.ui.KioskActivity`.

### Inicio, el PIN sólo para Configuración y el ECG en espera (1.4.0)

Pedidos del usuario probando en la tablet (rk3576, 1920×1080 a 160 dpi), sin plan escrito.

- **Escala.** Todo se veía chico: la tablet es 1920×1080 dp. `UiScale` sube la densidad para que el lado corto mida 800 dp (1422×800 dp, como el diseño de 1280×800), en todas las pantallas nativas; en el kiosco sólo el panel lateral y los diálogos (la página no). Los vitales del Panel Local pasan a 400 dp con `values-w1400dp`.
- **Inicio** (`HomeActivity`) reemplaza al kiosco como pantalla de inicio de Android (`HOME`) y es la del ícono de Berry Med. Tres cards: ONDOCTOR365 (con el corazón-estetoscopio del logo, redibujado en vector desde un PNG de 29 px), Panel Local y Configuración. Todo vuelve ahí: Finalizar, atrás, la casita del panel lateral. Esto reemplaza el punto 5 del plan 17 (el kiosco ya no es `HOME`; el comando es `set-home-activity …/.ui.HomeActivity`). Inicio va a pantalla completa con el panel lateral, sin Modo ni casita, y con «Listo» como en Local, porque en reposo el hub apaga el otoscopio y el estetoscopio.
- **El PIN sólo para Configuración.** El Panel Local entra sin PIN (sólo espera si hay una consulta enviando) y la casita del kiosco confirma sin PIN. Con una consulta en curso la casita del kiosco no deja salir ni recargar, como el cambio de modo; los cinco toques sí, para el técnico. El campo sigue siendo `localPanelPin`.
- **Configuración**: flecha (hace lo mismo que atrás), breadcrumb Inicio › Configuración › USB-config, sin los botones del Panel Local y ONDOCTOR365, y la fecha del último Guardar de USB-config (`savedAt`, que el sonido del panel lateral no cambia).
- **ECG en espera.** Las líneas verdes quietas cansaban la vista: el ECG arranca en espera (grilla apagada, sin ondas), arranca con Iniciar medición o tocándolo, y Detener lo vuelve a la espera. Midiendo sin señal (electrodos sueltos según la plaqueta, o la II con menos de 0,1 mV en 2 s) las ondas van en gris con un aviso. Detener va sobre el papel porque en la toolbar no entra.
- **Dobles toques.** Medir dos veces cancelaba la presión recién empezada y Detener dos veces volvía a arrancar el ECG: un segundo toque dentro de 0,7 s se ignora.
- Detalles: panel lateral cerrado al arrancar y con fondo azul para distinguirlo; la tarjeta del PIN más arriba del teclado; «Temperatura» con el valor «36,4 °C» y «Apretá el botón para medir».

## 15 de noviembre de 2026

### El otoscopio y «Computer»

Visto en la tablet de pruebas y confirmado con el código del proveedor Koplak, que usa el mismo otoscopio (`349C:3301`, PAKISS Q10):

- El otoscopio no manda imagen hasta que alguien elige **«Computer»** en el menú de su pantalla. El menú aparece cuando detecta un equipo que le habla por USB; si nadie elige, al minuto y medio se desconecta.
- Al elegir «Computer» se desenchufa y se vuelve a enchufar solo, con otro `deviceId`. `UvcOtoscope` se quedaba con el de antes y la imagen no llegaba nunca, con el encabezado diciendo «En vivo».
- Ahora, con el otoscopio abierto, se mira una vez por segundo si cambió el `deviceId`; si cambió, se espera 1,2 s (el video no está listo en el primer instante) y se abre de nuevo. La regla, `UvcOtoscope.Reconnect`, con test.
- El panel dice «Esperando imagen» y muestra el pedido de elegir «Computer» hasta que llega el primer segundo de cuadros; «En vivo» y **Congelar** recién con imagen.
- Se probó bajar a 480×480 (lo que pide el proveedor): no cambia la cadencia, que en la tablet fue de 5 a 9 cuadros por segundo con las dos resoluciones. Queda 800×800. El otoscopio declara 30 y 5 cuadros; falta confirmar con buena luz si baja solo cuando está oscuro.
- Se descartó en el camino que fuera falta de corriente o el cableado: enchufado directo a una Mac se comporta igual (sin elegir «Computer» se va solo).

### El YKUSH3 al arrancar la tablet

- Si el hub no contesta en los primeros segundos del arranque, el kernel de la tablet lo intenta dos veces (`device not accepting address`, `unable to enumerate USB device`) y no reintenta más: el hub queda prendido, con su luz verde, y la tablet no ve ni el hub ni lo que cuelga de él. Volver a buscarlo (desenlazar y enlazar el hub de arriba, con root) lo recupera al instante.
- Enchufar o desenchufar un equipo con todo prendido también lo dejó colgado una vez; ahí hizo falta cortarle la corriente.
- La app no puede forzar esa búsqueda sin root. Pendiente: un ajuste de sistema en la tablet que la repita si el hub no apareció al arrancar.

## 27 de noviembre de 2026

### Berry Med en español, inglés y portugués (plan 19, 1.5.0)

- La app está en **español** (el de origen, rioplatense, sin cambios), **inglés** de Estados Unidos y **portugués** de Brasil. Se eligió el **diccionario de Android** (`values/`, `values-en/`, `values-pt/`) y no uno propio: ya había 300 entradas, los layouts usan `@string`, y se ganan el chequeo al compilar, los plurales, el lint y el seudo-idioma. El idioma lo cambia AppCompat (`setApplicationLocales`), que en Android 13 o más se lo pasa al sistema.
- Lo que arma texto fuera de una pantalla (modelos, runtime, servicio, enlaces con los equipos) devuelve un **`Txt`** (clave y parámetros) en vez de una frase: las reglas siguen sin Android y con test, y las que comparaban frases comparan claves.
- **Sin idioma elegido, español**, no el idioma de la tablet: una tablet con el sistema en inglés no pasa a inglés al actualizar. En Android 13 o más lo fija `BerryMedApp` con el `LocaleManager`. En el emulador se vieron dos trampas y se resolvieron: la primera pantalla la arma Android antes de que arranque el proceso (salía en inglés; ahora cada pantalla pasa por `AppLocale.wrap`), y AppCompat, la primera vez, copiaba al sistema su idioma guardado (ninguno) encima del español.
- **El log, siempre en español** (`LogText`), con los números en `Locale.US`: soporte lee un solo idioma y el README cita líneas textuales. La consola de Testing queda en español.
- **El selector**, a la vista en Inicio, y **bloqueado con una consulta en curso** (signos enviando o la teleconsulta en sala), con la misma regla que el cambio de modo: cambiar el idioma recrea las pantallas, el kiosco recarga la web y se cortaría la videollamada.
- **Fechas y números** salen de `fmt_locale` y de los patrones del mismo diccionario (`es-AR`, `en-US`, `pt-BR`), no de la configuración de la tablet: nunca una fecha en otro idioma que el texto que la rodea. La hora va en 24 h en los tres. Lo que va a la API no cambia (`Locale.US`).
- Las traducciones las redactó un modelo. El vocabulario del producto (maletín → care kit / maleta, tótem → totem, kiosco → kiosk / quiosque, plaqueta → board / placa, Panel Local → Local Panel / Painel Local, teleconsulta → telehealth / teleconsulta) y los términos clínicos están en `docs/glosario-idiomas.md`.
- **Queda por confirmar en la tablet y por una persona:**
  - Las pruebas T1 a T5 del plan 19 en la tablet: actualizar de 1.4.0 a 1.5.0 y que siga en español con la configuración intacta; elegir inglés y reiniciar (Inicio y la notificación en inglés al encender); una teleconsulta de prueba en inglés con el selector bloqueado y la llamada sin cortarse; el Panel Local en portugués con los equipos (otoscopio y estetoscopio); y el log de esas pruebas en español.
  - La revisión clínica del glosario por un médico en inglés y en portugués, sobre todo lo marcado «a confirmar» (las arritmias, `brpm` / `irpm`, los modos del ECG y de la presión).
  - Android 12 o menos (el español por defecto lo fija la primera pantalla): no hay imagen de sistema para probarlo.

### Nombre visible: ONDOCTOR365 Suitcase (1.5.1)

- La app se llama **ONDOCTOR365 Suitcase**, el mismo texto en español, inglés y portugués, sin traducir: es lo que dicen el launcher, Ajustes → Apps, el cartel de Android para elegir la app de inicio y las frases que la nombran (la puesta en marcha del paso Kiosco, los permisos, la ayuda de Testing, la notificación). Lo eligió Nico. Antes, Berry Med.
- El canal de la notificación pasa a «Tótem ONDOCTOR365» / «ONDOCTOR365 totem» / «Totem ONDOCTOR365».
- El APK de las releases se llama `ondoctor365-suitcase-android-<versionName>.apk` desde la 1.5.1 (antes `berry-med-<versionName>.apk`).
- **No cambia**: el paquete `com.guardiavirtual.berrymed` (cambiarlo es otra app para Android: habría que desinstalar en cada tablet y se perdería la configuración), el namespace, las clases (`BerryMedApp`, `BerryConfig`…), los ids de recursos, `window.BerryMed` (el contrato con la web del kiosco), el nombre del repo, el log (ninguna línea nombraba a la app) y lo que dice «Berry» por la plaqueta PM6750, que es un producto de Berry. La card ONDOCTOR365 de Inicio, que abre el kiosco, sigue igual.
- Un test (`StringsCatalogTest`) falla si una entrada de cualquier diccionario vuelve a decir «Berry Med».
- Queda por ver en la tablet: el nombre bajo el ícono en su launcher y en el cartel «Select a Home app», y que al actualizar de 1.5.0 a 1.5.1 siga elegida como app de inicio (el paquete y la actividad son los mismos, así que no debería preguntar).

## Pendiente

- El otoscopio en la teleconsulta (`UvcOtoscopeFrames`) no reabre al reconectarse: si el médico lo pide antes de que alguien elija «Computer», falla. El panel lateral dice «En vivo» apenas abre, sin esperar la imagen. La cadencia del otoscopio (5 a 9 cuadros por segundo), a confirmar con buena luz.
- Que la tablet reintente tomar el YKUSH3 si no apareció al arrancar (ajuste de sistema), y probar los puertos siempre encendidos con el audio de la videollamada.
- Prueba con el hardware real en la tablet (ver "Probar el Panel Local en el tótem" en el `README.md` y los riesgos de `docs/hardware.md`).
- Los hallazgos 8 a 14, 16, 17 y 19 a 26 de `docs/revisiones/revision-codigo-opus.md` (el 15 y el 18 se resolvieron en el plan 15).
- Plan 15 en la tablet real: una temperatura tomada en el Panel Local no aparece al cerrarlo ni en la consulta siguiente; con el inicio perdido, una lectura de hace más de 60 s no llega al médico y una reciente sí; el panel abre sin presión hasta Medir.
- Duda a confirmar en la tablet (plan 15): si durante una medición nueva las tramas de presión traen el resultado anterior. Si lo traen, después de **Medir** en el panel, o de un `start-blood-pressure` en una consulta, la presión vieja volvería a aparecer con la hora de ahora (`resetNibp` borra el valor y `nibpResultSeen`, y la primera trama con el par viejo lo vuelve a poner) y, en la consulta, saldría a OnDoctor como presión del paciente hasta el resultado nuevo. No se tocó: mirar en Testing qué presión se ve mientras el manguito infla después de una medición anterior.
- Plan 6 en la tablet real: Zoom dentro del `WebView`, el camino UVC sin vista, el adaptador con `UNPROCESSED` y el kiosco como app de inicio (ver "Qué probar en la tablet" en el PR #19).
- Plan 7 en la tablet real: modo y uso de audio de Zoom en el `WebView`, y el protocolo del hub contra el YKUSH3 (ver riesgos en `docs/hardware.md`).
- Plan 9 en la tablet real: los cuadros por segundo del otoscopio por UVC, solo y con el estetoscopio escuchando.
- Plan 10 en la tablet real: el estetoscopio y la videollamada por el jack (ver riesgos en `docs/hardware.md`).
- Plan 14 en la tablet real: unos 30 cuadros/s en Testing → Teleconsulta con el otoscopio por UVC, que el médico vea la imagen y que no haya una línea `[TELE] La superficie del otoscopio por UVC no se pudo vaciar`.
- Plan 12 en la tablet real: los tres puertos encendidos con el kiosco, el otoscopio en la llamada sin pedido de permiso y el micrófono de la llamada con el adaptador y el otoscopio encendidos.
- Plan 13 en la tablet real: la luz del puerto 3 siempre encendida y el termómetro midiendo en el Panel Local y en una consulta.
- Plan 16 en la tablet real: el kiosco a 984 dp (la videollamada y los botones de la consulta), el ECG de siete derivaciones a 636 dp, los umbrales de dBm de las barras, los estados de cada dispositivo con la plaqueta, el hub, el termómetro y el jack, y el SSID con el permiso de ubicación.
- **Cero carteles USB después de reiniciar** (plan 17): Android no da una forma a una app común. Hace falta algo a nivel sistema: una ROM con `config_disableUsbPermissionDialogs`, o Berry Med instalada como app privilegiada con `MANAGE_USB`. Se decide aparte.
- Plan 17 en la tablet real: los pedidos USB de a uno al reiniciar (plaqueta, hub y termómetro, cada uno después de contestar el anterior), las filas con «Falta permiso» y su toque, el permiso del otoscopio desde su fila, y que **Pedir permiso USB** (que también cicla el otoscopio) no deje dos diálogos a la vez.
- Plan 17 en el emulador: no estaba prendido al implementarlo. Falta ver el aviso «Sin conexión» en modo avión y que se cierra al volver la red, la fila del Wi-Fi con y sin red, los permisos juntos al abrir, una fila con permiso rechazado, y Berry Med como inicio tras `set-home-activity` y un reinicio.
- Plan 19 en la tablet (T1 a T5) y la revisión clínica de `docs/glosario-idiomas.md` antes de usar la app con pacientes en inglés o en portugués.
- v2 con guardado y PDF local.
