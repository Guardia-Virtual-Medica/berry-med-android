# Plan 17: que nada se trabe por permisos, y el Wi-Fi a mano

Plan para un agente (15 nov 2026). Sale de una prueba en la tablet (rk3576, Android 14) con la rama `feat/panel-lateral` (PR 41, plan 16). Quien usa el maletín no es técnico: no puede quedar frente a un equipo en rojo sin saber que falta un permiso, ni sin red sin saber cómo elegir una.

## Lo que se vio en la tablet

- Después de reiniciar, Android **no conserva** los permisos USB de lo que ya estaba enchufado al arrancar, aunque Berry Med sea la app predeterminada de esos equipos: hay que pedirlos de nuevo en cada arranque.
- `UsbHub.requestMissingPermissions` (`usb/UsbHub.kt:44`) pide en la misma vuelta la plaqueta, el hub YKUSH3 y el termómetro. Android abrió **un solo** `UsbPermissionActivity` (en el log, un `START` y después `onActivityRestartAttempt`): los otros dos pedidos se perdieron sin respuesta. Como `PermissionRequests` pide cada equipo una sola vez, el hub y el termómetro quedaron sin permiso y sin cartel, y el panel lateral los muestra en rojo sin decir por qué.
- El nombre de la red ya sale en el panel (plan 16), pero la fila del Wi-Fi sólo se puede tocar cuando no hay red (`ui/status/StatusPanelView.kt:49` y `:107`).

## 1. Los permisos USB se piden de a uno, y se vuelven a pedir

**Cambios.**

- Un solo pedido en vuelo. `requestMissingPermissions` pide el primer equipo que falta y no pide otro hasta que llegue la respuesta de ése (el broadcast `ACTION_USB_PERMISSION`, que hoy termina en `TotemRuntime.onUsbPermission`) o pase un vencimiento (proponé 60 s: un cartel que nadie contestó). Orden: plaqueta, hub YKUSH3, termómetro. El otoscopio sigue fuera de este pedido automático, como hoy (`OtoscopePermission`).
- La regla va en `PermissionRequests`, sin Android y con test: con un pedido en vuelo no se pide otro; al llegar la respuesta (aceptado o rechazado) se pide el siguiente; vencido el plazo, también; un equipo rechazado no se vuelve a pedir solo (espera al botón, ver abajo); un equipo que desaparece libera el pedido en vuelo.
- Mirá cómo llega el resultado: `permissionIntent()` usa un `PendingIntent` con `FLAG_UPDATE_CURRENT` y el mismo `requestCode`. Con un pedido por vez alcanza; confirmá que el broadcast trae el `UsbDevice` (`EXTRA_DEVICE`) para saber cuál se contestó.
- `forgetAsked()` (el botón **Pedir permiso USB**) sigue olvidando todo, pero ahora los pide en fila, no juntos.

## 2. El panel lateral dice que falta el permiso y deja pedirlo

**Qué pasa hoy.** Un equipo enchufado sin permiso se ve igual que uno desenchufado.

**Cambios.**

- `StatusSnapshot` ya distingue el permiso USB (plan 16): revisá qué muestra cada fila en ese caso. Tiene que quedar: equipo presente sin permiso → texto **«Falta permiso»**, tono de aviso (el ámbar que ya usa el panel, no el rojo de desconectado), y la fila se puede tocar.
- Tocar esa fila (o su ícono en el riel) pide el permiso de ese equipo: llama a lo mismo que **Pedir permiso USB** (`TotemRuntime.requestUsbPermissionAgain`). Para el otoscopio, lo que ya hace `askOtoscopePermission`.
- Vale para la plaqueta, el hub, el termómetro y el otoscopio. El estetoscopio es audio USB y no lleva permiso USB: si lo que le falta es el micrófono, ver punto 3.
- Las reglas en `StatusPanelUi`, con test en `StatusPanelUiTest`.

## 3. Los permisos de la app (cámara, micrófono, ubicación, notificaciones)

**Qué pasa hoy.** Se piden en tres lugares distintos: `MainActivity.askPermissions` (`:181`), `KioskActivity` (`:433`) y `LocalPanelActivity` (`:175-186`, al usar cada cosa). La ubicación (para el nombre de la red) sólo la pide el kiosco.

**Cambios.**

- Una sola función que diga qué permisos faltan (cámara, micrófono, ubicación fina y, desde Android 13, notificaciones), y las tres pantallas la usan para pedirlos **todos juntos al abrir**, no al tocar un instrumento. `LocalPanelActivity` puede conservar sus pedidos puntuales como respaldo si alguien los rechazó.
- Si alguno queda rechazado, el panel lateral lo dice en la fila que corresponde («Falta permiso», mismo estilo del punto 2) y tocarla lo vuelve a pedir; si Android ya no deja pedirlo (rechazado dos veces), abre los ajustes de la app (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`). Filas: cámara → otoscopio y cámara de la tablet; micrófono → estetoscopio; ubicación → Wi-Fi (el nombre de la red).
- **Instalación sin carteles.** En el README, el comando de instalación pasa a `adb install -r -g berry-med-<versión>.apk`: `-g` da de entrada todos los permisos de la app. Explicalo en una línea.

## 4. El Wi-Fi

Se usa el panel de Wi-Fi de Android (`Settings.Panel.ACTION_WIFI`, lo que ya devuelve `KioskActivity.wifiSettingsIntent()` en `:563` y repite `LocalPanelActivity` en `:347`): se abre encima de Berry Med, con la lista de redes y la clave. Un selector propio necesitaría que la app sea administradora del dispositivo; queda fuera.

**Cambios.**

- **La fila del Wi-Fi se toca siempre**, con red o sin red, y abre el panel de Wi-Fi. El chevron se ve siempre; con red, al lado de las barras. En el riel, tocar el ícono del Wi-Fi hace lo mismo.
- **El nombre de la red** se ve en la fila (ya está). Sin permiso de ubicación hoy no hay nombre: mostrá «Wi-Fi conectado» y aplicá el punto 3 para pedir la ubicación.
- **Aviso al abrir sin red.** Al abrir `MainActivity`, el kiosco o el Panel Local sin Wi-Fi, un diálogo con el estilo de la app (el de `ModeSwitchDialog`): título «Sin conexión», texto «Elegí una red Wi-Fi para usar el maletín.», botones **Elegir red** (abre el panel de Wi-Fi) y **Ahora no**. Reglas, sin Android y con test:
  - se muestra una vez por apertura de pantalla, no en cada segundo;
  - se cierra solo cuando llega la red;
  - no aparece arriba de la pantalla del PIN del Panel Local ni arriba de otro diálogo (el del PIN del kiosco, el de cambio de modo);
  - dale unos segundos de gracia al arrancar (la red tarda en subir después de prender): proponé 5 s sin red antes de mostrarlo.
- El intent del panel de Wi-Fi queda en un solo lugar, no copiado en cada pantalla.

## 5. Berry Med se abre sola al prender la tablet

Agregado el 15 de noviembre, pedido por el usuario: al prender la tablet tiene que quedar Berry Med adelante, en el kiosco, sin que nadie toque nada.

**Qué pasa hoy.** `BootReceiver` arranca el servicio, pero no hay pantalla: queda el inicio de Android. Desde Android 10 un receptor no puede abrir una pantalla desde el fondo.

**Cambios.**

- Berry Med se puede elegir como **pantalla de inicio**: una actividad con `<category android:name="android.intent.category.HOME" />` y `DEFAULT` en el `intent-filter` de `MAIN`. Así es Android el que la abre al arrancar y cada vez que se toca Inicio. Mirá si conviene sumarlo a `MainActivity` o a una actividad chica sin interfaz (como `OnDoctorLauncher`) que decida y termine; elegí lo que menos toque.
- Qué abre: si el kiosco está configurado (la URL del kiosco guardada; mirá cómo decide hoy `OnDoctorLauncher`), abre el kiosco; si no, `MainActivity`. La decisión espera a que la configuración esté leída (`configLoaded`), no usa los valores de fábrica. La regla, pura y con test.
- El aviso «Sin conexión» del punto 4 vale también acá: al prender sin red aparece arriba del kiosco, con **Elegir red**.
- **No quedar encerrado.** Siendo la pantalla de inicio, tiene que seguir habiendo una forma de llegar a los ajustes de Android: en la configuración de la app (la que ya está detrás del PIN), un botón **Ajustes de Android** (`Settings.ACTION_SETTINGS`). Si ya existe algo así, usalo.
- No se elige sola como inicio: eso se hace una vez por tablet con `adb shell cmd package set-home-activity <componente>`. Documentalo en el README, en la instalación, con el comando exacto y cómo volver atrás (elegir de nuevo el inicio de Android en Ajustes → Apps → Apps predeterminadas).
- En el emulador: verificá que tras `set-home-activity` y un reinicio (`adb -s emulator-5554 reboot`) queda Berry Med adelante, y que Inicio vuelve a la app. Después **dejá el emulador como estaba** (el inicio original).

## Lo que no entra

- **Cero carteles USB después de reiniciar.** Android no da una forma a una app común. Hace falta algo a nivel sistema (una ROM con `config_disableUsbPermissionDialogs`, o la app instalada como privilegiada con `MANAGE_USB`). Se decide aparte; dejalo anotado en `docs/decisiones.md` como pendiente.
- Selector de Wi-Fi propio y administrador de dispositivo.
- La versión: **no la cambies**.

## Tests y entrega

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.
- Si el emulador `berry_tablet` está prendido (`adb devices`; usá siempre `-s emulator-5554`, hay una tablet real conectada por red y **no se toca**), instalá ahí y verificá: el aviso sin red (modo avión) y que se cierra al volver la red; la fila del Wi-Fi abre el panel con red y sin red; los permisos de la app se piden juntos al abrir; una fila con permiso rechazado dice «Falta permiso» y al tocarla lo pide. El emulador no tiene USB: la fila de pedidos USB se confirma en la tablet; decilo.
- Documentación, en el mismo PR: `README.md` (instalación con `-g`, «Panel lateral», permisos USB de a uno) y `docs/decisiones.md` (entrada del 15 de noviembre de 2026 para el plan 17, con lo pendiente).
- Rama `feat/permisos-y-wifi` **desde `feat/panel-lateral`** (no desde `main`: el panel lateral todavía no está mergeado); PR contra `feat/panel-lateral`. Commits en español con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commiteá este plan.
- En el informe final: qué cambió por punto, qué decidiste donde el plan dejaba elegir, y qué quedó sin verificar.
