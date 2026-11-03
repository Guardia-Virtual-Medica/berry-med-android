# Plan 12: con el kiosco adelante, el otoscopio y el estetoscopio quedan encendidos

Plan para un agente (3 nov 2026). Sale de un reporte de campo con la 1.3.4: en la videollamada **el otoscopio no transmite y aparece un pedido de permiso**, mientras que en el Panel Local se ve. El usuario decidió: **mientras el kiosco esté adelante, los puertos del otoscopio y del estetoscopio del YKUSH3 quedan siempre encendidos**, no sólo durante la llamada. Sin cambios en la API ni en el kiosco web.

## Qué pasa hoy (leído en el código)

- **Reposo** (`service/HubRestState.kt`): otoscopio y estetoscopio apagados, auricular encendido si la llamada sale por él. `TotemRuntime.applyHubRest()` lo aplica una vez por hub (`HubRestState.Pending`), salvo que haya consulta, Testing, Panel Local o teleconsulta usando un equipo (`hubRestBlocked()`).
- **Teleconsulta** (`tele/LiveKitBackend.publishOtoscope`): al llegar `start_oth` prende el puerto del otoscopio (`powerOn`), espera **8 s** (`OTOSCOPE_WAIT_NANOS`) a que aparezca como cámara o **por USB con permiso** (`OtoscopeProbe.usbReady()`), y si no, **apaga el puerto** (`powerOff`) y devuelve "El otoscopio no aparece ni como cámara ni por USB con permiso".
- Cada encendido es un `deviceId` nuevo para Android. El permiso USB dado con `requestPermission` (el de `UsbHub.requestMissingPermissions`, que sale una vez por dispositivo) **se pierde al apagar el puerto**; sólo el diálogo del intent `USB_DEVICE_ATTACHED` (filtro `usb_device_filter.xml`, que incluye `349C:3301`) trae «Usar de forma predeterminada» y lo deja permanente.
- Resultado probable en la llamada: el pedido aparece arriba de Zoom, nadie lo acepta en 8 s, el puerto se apaga y el permiso ya no sirve; la vez siguiente, dispositivo nuevo y otra vez lo mismo.
- **Panel Local** (`TotemRuntime`, alrededor de la línea 869): si a los 8 s el otoscopio está enchufado pero sin permiso, **deja el puerto encendido**, vuelve a pedir el permiso (`hub.forgetAsked()`) y avisa. Por eso ahí se ve.

## Decisiones

0. **Esto es sólo alimentación, no transmisión.** Encender un puerto le da energía al equipo para que Android lo vea (y conserve su permiso USB); no publica nada. La transmisión a LiveKit sigue empezando sólo con la orden de siempre: el botón Otoscopio o Estetoscopio del kiosco manda `start_oth`/`start_steth` por Pusher, y `stop_oth`/`stop_steth` la cortan. Al cortar, el puerto queda encendido mientras siga el kiosco. Que el KDoc y el README lo digan así.
1. **Estado "kiosco"**: mientras `KioskActivity` está visible (entre `onStart` y `onStop`), el estado buscado de los puertos es **otoscopio encendido, estetoscopio encendido**, y el auricular como hoy (encendido si la llamada sale por él). Fuera del kiosco (configuración, Testing, Panel Local, otra app) vale el reposo de hoy: otoscopio y estetoscopio apagados.
   - `HubRestState.wanted(config, kiosk: Boolean)`: con `kiosk = true`, los puertos `ykushOtoscopePort` y `ykushStethoscopePort` en `true`. Tests para las dos ramas (y un puerto libre sigue en `null`).
   - `TotemRuntime.setKioskVisible(visible: Boolean)` (o el nombre que encaje), llamado desde `KioskActivity.onStart`/`onStop`. Al cambiar, `hubRest.again()` para que `applyHubRest()` lo aplique en la vuelta siguiente del `watchLoop` (cada 1 s). El diálogo del PIN no dispara `onStop`: no apaga nada.
   - `hubRestBlocked()` no cambia: con Testing, Panel Local o una consulta enviando no se toca nada y se aplica al liberarse. Con la **teleconsulta usando un equipo** tampoco se toca (no se apaga nada a mitad de una llamada).
   - Log: `[YKUSH] Puertos con el kiosco: puerto 1 encendido, puerto 2 encendido, puerto 3 encendido` y el de reposo como hoy.
2. **La teleconsulta usa los puertos ya encendidos**: `powerOn` ya no enciende lo que está encendido y `PanelPorts.claim` no anota un puerto que se leyó encendido, así que al terminar no lo apaga. Confirmalo con un test de `PanelPorts` si no lo hay, y no cambies esa regla.
3. **La teleconsulta espera el permiso como el Panel Local**: en `publishOtoscope`, si a los 8 s el otoscopio está **enchufado pero sin permiso** (`probe.usbPresent() && !probe.usbReady()`), **no apaga el puerto**, vuelve a pedir el permiso (`hub.forgetAsked()` o lo equivalente que tenga a mano el backend) y devuelve un motivo claro: "Falta aceptar el permiso USB del otoscopio en la tablet". Si el puerto lo había encendido la teleconsulta, se apaga recién al cortar, como hoy. Un `start_oth` siguiente vuelve a intentar con el mismo dispositivo, que ya tiene el permiso si alguien lo aceptó. Lo mismo para el estetoscopio si su camino tiene una espera equivalente (revisalo: es audio, normalmente no pide permiso USB).
4. **Arranque del hub** (`Guardar este arranque en el hub`, comando 0x41): pasa a guardar el estado del kiosco (otoscopio y estetoscopio encendidos), porque la tablet arranca en el kiosco. Ajustá el texto de ayuda de Testing y el log.
5. **Permiso permanente**: el punto "Permiso USB del otoscopio" del paso Kiosco sigue igual (encender en Testing, marcar «Usar de forma predeterminada», apagar), pero el texto pasa a decir que con el kiosco el puerto queda encendido, así que alcanza con aceptarlo una vez marcando la casilla. Si en el código ves que `requestMissingPermissions` pide el permiso del otoscopio con `requestPermission` (sin casilla) al mismo tiempo que Android muestra el diálogo del intent (con casilla), decilo en el PR: los dos diálogos a la vez confunden y conviene que el primero sea el de la casilla. No lo cambies sin decirlo.

## Riesgos a escribir en `docs/hardware.md` (no se pueden probar en el emulador)

- **Audio de la llamada con dos placas de audio USB más encendidas**: el adaptador del estetoscopio (entrada mono, salida estéreo) y el micrófono del otoscopio (USB Audio mono 32 kHz) quedan presentes durante toda la videollamada. `CallAudioRouter` fija la salida (jack, H390 o parlante); hay que confirmar en la tablet que **el micrófono de la llamada** no pase al adaptador del estetoscopio ni al del otoscopio. Si `CallAudioRouter` puede excluirlos explícitamente al elegir el dispositivo de comunicación, que lo haga y lo diga; si no, que Testing → Teleconsulta muestre qué entrada usa la llamada para poder verlo.
- Consumo: tres puertos encendidos todo el tiempo (el hub está alimentado; anotarlo).

## Tests

- `HubRestStateTest`: con y sin kiosco, con auricular y con parlante, con puertos libres.
- Si la regla de la espera del otoscopio en la teleconsulta se puede sacar a una función sin Android (como `OtoscopePower`), testearla: enchufado sin permiso → no apagar y pedir de nuevo; no enchufado → apagar como hoy.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.

## Verificación

El emulador `berry_tablet` (`emulator-5554`) no tiene hub: ahí sólo se ve en el log que al abrir el kiosco se busca el estado "kiosco" y al ir a la configuración el reposo (o que no hay hub y no pasa nada). **Decirlo, no darlo por probado.** En la tablet: con el kiosco adelante, Testing → Hub YKUSH3 → Leer estado muestra 1, 2 y 3 encendidos; en una llamada, `start_oth` publica el otoscopio sin pedido de permiso (si se marcó la casilla) o, si no, el pedido queda y al aceptarlo el siguiente `start_oth` publica; la llamada sigue con el micrófono del auricular.

## Documentación

- README: Hub YKUSH3 (estado con el kiosco y en reposo), paso Kiosco (permiso del otoscopio), teleconsulta y la tabla de problemas ("El otoscopio no transmite en la videollamada": aceptar el permiso marcando la casilla).
- `docs/hardware.md`: los riesgos de arriba.
- `docs/decisiones.md`: entrada con esta decisión del usuario (reemplaza la del plan 7 que dejaba otoscopio y estetoscopio apagados en reposo **con el kiosco**).
- Commitear este plan.

## Entrega

Rama `feat/puertos-kiosco` desde `chore/release-1.3.4` (los PR 30, 31 y 32 todavía no están en `main`). PR contra `chore/release-1.3.4`, para que el diff muestre sólo esto; cuando el 32 se mergee, se cambia la base a `main`. Commits en español en el estilo del repo, con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No cambiar la versión. No tocar `local.properties`, `docs/capturas/` ni `docs/apk/`.

## Parte 2: un solo pedido de permiso para el otoscopio, el que tiene la casilla (pedido del usuario, 3 nov 2026)

Al revisar la parte 1 se vio que salen **dos pedidos de permiso del otoscopio casi juntos**. Uno es el de Android por el intent `USB_DEVICE_ATTACHED` (filtro `usb_device_filter.xml`), que trae **«Usar de forma predeterminada»**. El otro es el de `UsbHub.requestMissingPermissions`, que llama a `requestPermission` unos 3 s después y **no trae la casilla**. Si se acepta el segundo, el permiso vale sólo hasta que se apaga el puerto. El usuario decidió sacarlo: **para el otoscopio, Berry Med no pide el permiso solo**; queda el pedido de Android con la casilla. Misma rama `feat/puertos-kiosco`, commit aparte, mismo PR (#33).

- **Pedido automático**: `UsbHub.requestMissingPermissions()` deja afuera al otoscopio (`KnownDevices.isOtoscope`). La plaqueta, el termómetro y el YKUSH3 siguen igual. `permissionMissing()` sí lo sigue contando, para que la pantalla diga que falta.
- **Volver a pedirlo a propósito** (el botón **Pedir permiso USB** de la ruedita, `probe.askPermissionAgain()` de la teleconsulta y el Panel Local): para el otoscopio sin permiso, si hay YKUSH3 y se sabe su puerto, **se apaga y se vuelve a encender el puerto del otoscopio** (unos 2 s apagado). Android lo ve como recién conectado y vuelve a mostrar el pedido **con la casilla**. Sólo si no hay hub, o el hub no contesta, se cae al `requestPermission` de hoy, sin casilla, y se dice en el log.
  - Nunca con el otoscopio publicando o abierto en el Panel Local: ahí ya tiene permiso, así que no aplica. Pero que la regla lo verifique (`hasPermission` del dispositivo justo antes de apagar).
  - Ese ciclo de apagar y encender no debe pelearse con el estado del kiosco ni con `PanelPorts`: el puerto vuelve a quedar como estaba (encendido), y no se anota como de nadie.
  - Una función sin Android que decida qué hacer (ciclo del puerto, `requestPermission` o nada), con test: con hub y puerto conocido → ciclo; sin hub → `requestPermission`; con permiso → nada.
- **Log**: `[USB] Permiso del otoscopio: se apaga y enciende el puerto N para que Android lo pida con «Usar de forma predeterminada»` y el de respaldo.
- **Textos**: la ayuda del punto "Permiso USB del otoscopio" del paso Kiosco y el README dicen que el pedido es uno solo y que hay que marcar la casilla; si se cerró sin aceptar, **Pedir permiso USB** lo hace aparecer de nuevo. Sacar de `docs/hardware.md` y `docs/decisiones.md` lo de "dos pedidos a la vez" como pendiente y anotarlo como resuelto.
- **Verificación**: tests y `assembleDebug` en verde. En el emulador no hay otoscopio ni hub: decirlo. En la tablet, con el puerto del otoscopio encendido y sin permiso, tiene que aparecer **un solo** pedido, con la casilla.
