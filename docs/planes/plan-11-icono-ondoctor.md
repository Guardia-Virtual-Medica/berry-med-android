# Plan 11: ícono y botón **ONDOCTOR365** que abren el kiosco, y la pantalla principal con botones de verdad

Plan para un agente (2 nov 2026). Pedido del usuario: un botón **OnDoctor** en Berry Med que lleve directo a la URL de OnDoctor en modo kiosco. El usuario eligió hacerlo con el kiosco que ya corre **dentro de Berry Med** (`KioskActivity`, un `WebView`), no abriendo Chrome. Sin cambios en la API ni en el kiosco web (`gvm.mt.totem`).

## Qué pasa hoy

- El kiosco es `ui/KioskActivity.kt`: `WebView` a pantalla completa con la URL guardada en la configuración (`BerryConfig.kioskUrl`), sin barras del sistema, atrás no sale, se sale con cinco toques arriba a la izquierda más el PIN. Es la app de inicio (HOME) de la tablet, pero **no tiene ícono**: si la tablet tiene otro launcher elegido, o se entró a la configuración, no hay una forma obvia de volver al kiosco salvo el botón de inicio (y sólo si Berry Med es la app de inicio).
- La única entrada a mano es el botón de la fila **Kiosco del tótem** del paso Kiosco del configurador (`binding.checkTotem.action`, `MainActivity.kt`), escondido al final de una lista.
- El Panel Local ya tiene su propio ícono: `LocalPanelActivity` en el manifest con `android:icon="@mipmap/ic_launcher_local"`, el mismo dibujo (`@drawable/ic_launcher_fg`) sobre fondo `@color/local_icon` (azul), y `taskAffinity` propia.

## Por qué no Chrome (para el KDoc y `docs/decisiones.md`)

Chrome en Android no tiene modo kiosco: muestra la barra de direcciones y el paciente puede irse; bloquearlo exige hacer a Berry Med "device owner" (reset de fábrica). Además se pierde lo del plan 6: Berry Med deja de ver la URL con `serverUrl`/`token` de LiveKit (el médico no recibe otoscopio ni estetoscopio) y Android vuelve a silenciar el estetoscopio mientras Zoom tiene el micrófono, porque ya no son la misma app.

## Decisiones

- **Tercer ícono en la tablet, "ONDOCTOR365"**: un `<activity-alias>` que apunta a `.ui.KioskActivity`, con `MAIN` + `LAUNCHER`, `android:label="@string/ondoctor"` ("ONDOCTOR365") e ícono propio `@mipmap/ic_launcher_ondoctor`. Un alias y no otra actividad: el kiosco es uno solo, con su `WebView`, su sala de LiveKit y su recarga. El filtro HOME queda en `KioskActivity` como está.
- **Ícono**: el mismo dibujo de Berry Med (`@drawable/ic_launcher_fg`) sobre un fondo nuevo `@color/ondoctor_icon` = `#5B3FA8` (violeta), distinto del verde de Berry Med (`@color/signal`, `#1F6F62`) y del azul del Panel Local (`#1E4E8C`). Adaptive icon en `mipmap-anydpi-v26/ic_launcher_ondoctor.xml`, con el mismo comentario de estilo que `ic_launcher_local.xml`. Si hay PNG de respaldo para `ic_launcher_local` en otras densidades, hacer lo mismo; si no hay, tampoco (minSdk lo dice). Si el usuario después manda un logo de OnDoctor, se cambia sólo el foreground.
- **Botón "ONDOCTOR365" en la pantalla principal**: abre `KioskActivity`. Va en la fila de botones junto con los otros (ver "Pantalla principal" abajo).
- **La etiqueta del ícono** también es "ONDOCTOR365" (`@string/ondoctor` = "ONDOCTOR365"), igual que el botón. El nombre de la app (`app_name` = "Berry Med") no cambia.
- **Sin PIN para entrar al kiosco**: es la pantalla del paciente, entrar no da acceso a nada. El PIN sigue para salir.
- **URL**: la del kiosco que ya está guardada. Sin URL, el kiosco ya muestra "Falta la URL" con el botón a la configuración: no cambia. No se agrega ninguna URL nueva a la configuración.
- **Una sola instancia y sin recargar**: volver al kiosco desde el ícono o el botón no tiene que destruir el `WebView` ni recargar la página (hoy `show()` no recarga si la URL guardada es la misma: conservarlo). Si había una videollamada o una sala de LiveKit, sigue.
- **Tareas**: `KioskActivity` y `MainActivity` son `singleTask` con la misma afinidad (el paquete). Con un ícono propio eso trae dos problemas posibles: tocar el ícono de **Berry Med** con el kiosco arriba en la misma tarea puede mostrar el kiosco en vez de la configuración, o al revés, `singleTask` de `MainActivity` puede **destruir el kiosco** que está encima (limpia lo que tiene arriba). Darle al kiosco su propia tarea con `android:taskAffinity="com.guardiavirtual.berrymed.kiosk"`, como el Panel Local tiene la suya. Comprobar en el emulador que el kiosco siga funcionando como app de inicio con esa afinidad (botón de inicio, arranque de la tablet con `BootReceiver`) y que la salida con PIN a la configuración y la vuelta con el botón ONDOCTOR365 no recreen el kiosco (`onCreate` una sola vez: mirarlo en el log). Si la afinidad propia rompe algo del HOME, explicarlo y proponer otra salida antes de inventar.
- **Panel Local**: no cambia en este plan (Finalizar sigue cerrando el panel). Anotar en el PR, como pregunta para el usuario, si Finalizar debería llevar al kiosco.

## Pantalla principal (pedido del usuario, 2 nov 2026, con la maqueta que se revisó en la conversación)

Sólo la cabecera y la fila de botones de `activity_main.xml`; el resto de la pantalla (vitales, estados, pasos de la configuración) queda como está.

- **Título**: `screen_title` pasa de "Berry Med PM-6750" a "Maletín de atención ONDOCTOR365".
- **Ruedita de ajustes arriba a la derecha**, en la misma fila que el título (el título a la izquierda con `weight 1`, la ruedita a la derecha). Ícono nuevo `drawable/ic_settings.xml` de Material Symbols Rounded, peso 400, sin relleno, con el mismo comentario de licencia que `ic_videocam.xml`; tinte `@color/vital_on`; área táctil de 48 dp como mínimo, con ripple circular y `contentDescription` "Ajustes". Al tocarla abre un `PopupMenu` (o un menú equivalente con el tema oscuro de la pantalla) con una sola opción, **Evitar que se duerma**, que llama a `askBatteryExemption()`. El botón `batteryButton` sale de la fila. El botón de la fila Sin optimización de batería del paso Kiosco sigue igual.
- **Fila de botones**, cuatro, en este orden: **USB-config** (el `usbButton` de hoy: `usb_permission` pasa de "Pedir permiso USB" a "USB-config"; hace lo mismo que hoy), **Testing**, **Panel Local**, **ONDOCTOR365** (nuevo `ondoctorButton`).
- **Que parezcan botones, no badges**: hoy son `OutlinedButton` con borde fino y forma de píldora, y el usuario dice que se ven como etiquetas. Pasan a botones rellenos con texto: `Widget.Material3.Button.TonalButton` (o `Button` con `backgroundTint`) con fondo `@color/card` (`#244255`) y texto `@color/vital_on`; **ONDOCTOR365** relleno con `@color/ondoctor_icon` (`#5B3FA8`), el mismo violeta del ícono, porque es la acción principal. Esquinas de 10 dp (no píldora), sin borde, con el ripple de Material al tocar (la respuesta al toque es lo que pide el usuario: que "hagan de botones"). Un estilo `@style/MainButton` en `themes.xml`/`styles.xml` para no repetir atributos, y `MainButton.Primary` para el violeta.
- **Más grandes**: alto mínimo 56 dp (hoy ~40), texto 16sp con peso medio, margen de 10 dp entre botones. Los cuatro en una sola fila con `weight 1`; si en 1280×800 dp algún texto no entra entero, decilo en el PR antes de achicar la letra.
- Textos que nombran los botones viejos: README líneas que dicen **Pedir permiso USB** pasan a **USB-config**, y las de **Evitar que se duerma** explican que está en la ruedita de arriba a la derecha. Buscar también en `strings.xml` (ayudas) y en `docs/`.

## Archivos

1. `app/src/main/AndroidManifest.xml`: el `<activity-alias>` (con comentario en el estilo del archivo: qué es y por qué alias) y `taskAffinity` en `KioskActivity`.
2. `app/src/main/res/mipmap-anydpi-v26/ic_launcher_ondoctor.xml` (+ densidades si corresponde) y `@color/ondoctor_icon` en `values/colors.xml`.
3. `app/src/main/res/values/strings.xml`: `ondoctor` = "ONDOCTOR365", `screen_title`, `usb_permission`, `settings` = "Ajustes". Si hace falta una ayuda corta debajo de los botones, en el tono del resto (voseo, sin tecnicismos), por ejemplo: "ONDOCTOR365 abre el kiosco del paciente; para volver acá, cinco toques arriba a la izquierda y el PIN."
4. `app/src/main/res/layout/activity_main.xml` y `ui/MainActivity.kt`: cabecera con la ruedita y su menú, la fila de cuatro botones, `ondoctorButton` → `startActivity(Intent(this, KioskActivity::class.java))`. `drawable/ic_settings.xml` y los estilos `MainButton`.
5. KDoc de `KioskActivity`: sumar que se entra también por el ícono y el botón ONDOCTOR365, y que tiene tarea propia.

## Tests

- No hay lógica nueva que testear en JVM. Si el proyecto no tiene Robolectric, no agregarlo por esto.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde (ningún test existente roto).
- `aapt2 dump badging` (o `apkanalyzer manifest print`) del APK debug: tiene que listar tres `launchable-activity` (Berry Med, Panel Local, ONDOCTOR365), con sus etiquetas.

## Verificación en el emulador (`berry_tablet`, `emulator-5554`)

Con una URL de kiosco guardada (la de Dev, `kiosks-dev.ondoctor365.com/clinicaalem/login`):

1. Con el launcher del emulador como app de inicio: el cajón de apps muestra tres íconos distinguibles. Captura de pantalla.
1b. Pantalla principal: título nuevo, la ruedita abre el menú con Evitar que se duerma (y pide la exención), los cuatro botones con el ripple al tocar y los textos enteros. Captura de pantalla para el PR.
2. Tocar **ONDOCTOR365**: abre el kiosco a pantalla completa con la URL, sin barras; atrás no sale.
3. Cinco toques + PIN → configuración → botón **ONDOCTOR365**: vuelve al mismo kiosco sin recargar (log `[KIOSCO] Se abre …` una sola vez).
4. Desde el kiosco, salir con el PIN y tocar el ícono de **Berry Med**: se ve la configuración, no el kiosco; volver con el ícono ONDOCTOR365: el kiosco sigue vivo.
5. Con Berry Med como app de inicio: el botón de inicio sigue abriendo el kiosco, y reiniciar el emulador también.

Lo que no se puede probar en el emulador (videollamada Zoom real, token real) se dice, no se da por probado.

## Documentación

- README: en la parte del kiosco y en "Cómo se entra" del Panel Local, los tres íconos (Berry Med = configuración, Panel Local = presencial, ONDOCTOR365 = kiosco del paciente) y el botón de la pantalla principal.
- `docs/decisiones.md`: entrada con estas decisiones y el porqué de no usar Chrome.
- Commitear este plan.

## Entrega

Rama `feat/icono-ondoctor` desde `main`, commits en español en el estilo del repo, PR contra `main`. Independiente de los planes 9 y 10 (si alguno ya está mergeado, partir de `main` actualizado; si chocan `strings.xml` o el README, resolverlo sin perder lo del otro). No cambiar la versión (`versionCode`/`versionName`). No tocar `local.properties`, `docs/capturas/` ni `docs/apk/`.

## Parte 2: la pantalla principal con el estilo del Panel Local (pedido del usuario, 2 nov 2026)

Al ver la parte 1 en el emulador, el usuario pidió dos cosas: que la configuración (la tarjeta blanca con los pasos Destino, Órdenes, Plaqueta y Kiosco) **no esté siempre a la vista** sino que se abra con **USB-config**, y que la pantalla principal **se vea como el Panel Local** (letras, fondo, tarjetas, botones), "así no parecen dos apps distintas". Hubo una maqueta en la conversación; esto la describe. Va en la misma rama `feat/icono-ondoctor`, en commits aparte.

### Qué cambia de comportamiento

- **USB-config abre la configuración**: la tarjeta de pasos (hoy el `ConstraintLayout` con `ThemeOverlay.BerryMed.Form`, `activity_main.xml` desde la línea ~219) sale del scroll principal y pasa a una **capa** que ocupa el lugar del contenido debajo de la cabecera, como los paneles de dispositivo del Panel Local. Arriba a la izquierda de la capa, un botón **←** y el título **Configuración**; el gesto de atrás también la cierra (`OnBackPressedCallback` habilitado sólo con la capa abierta). Cerrarla no guarda: guardar sigue siendo el botón **Guardar** del último paso, como hoy. Al guardar bien, la capa se queda abierta con el mensaje de "guardado" (como hoy); el usuario la cierra.
- **Se abre sola** cuando no hay configuración guardada o le falta el identificador del tótem (primera instalación), y cuando otra pantalla manda a la configuración: el botón "Abrir la configuración" del kiosco sin URL (`KioskActivity.buildEmptyView`) y el PIN del kiosco (`askPin` → Entrar). Para eso, un extra en el `Intent` (por ejemplo `MainActivity.EXTRA_OPEN_CONFIG`), leído en `onCreate` y en `onNewIntent` (MainActivity es `singleTask`). Decidí con criterio si el PIN del kiosco abre la configuración o la pantalla principal; lo esperable es la pantalla principal con la capa cerrada, salvo que falte la URL del kiosco.
- **Pedir permiso USB** pasa al menú de la ruedita, arriba de **Evitar que se duerma** (`runtime().requestUsbPermissionAgain()`, lo mismo que hoy). El botón de la fila deja de pedir permiso: ahora abre la configuración. El texto de ayuda de debajo de los botones (`help_usb_default`, el de «Usar de forma predeterminada») se muda adentro de la capa de configuración, en el paso Plaqueta, o al pie del menú: donde quede mejor, decilo en el PR.
- El nombre del botón sigue siendo **USB-config**, como pidió el usuario.

### Cómo se ve (los tokens del Panel Local, sin inventar colores)

- **Tema**: la pantalla principal pasa de `Theme.BerryMed` a `Theme.BerryMed.Panel` (fondo `@color/bg` `#0B0F14`, barras del sistema del mismo color, letra `@font/barlow_medium`). Sigue en vertical u horizontal como hoy (no forzar orientación). `TestingActivity` no cambia en este plan.
- **Cabecera** (fila fija arriba, fuera del scroll): título "Maletín de atención ONDOCTOR365" con `Panel.Text.Title` (Barlow SemiBold, puede ir a 20–22 sp); a la derecha, **chips** de estado con `Panel.Chip` y el punto `panel_dot` (verde conectado, ámbar conectando, gris sin dato): **Plaqueta**, **Pusher** y **Termómetro**, con la misma lógica de estado que ya usa la pantalla (`berryDot`, `pusherStatus`, termómetro en `otherStatus`; si hay una regla en el Panel Local, como "conectada si mandó datos en los últimos 3 s", reusala, no la dupliques). Al final, la ruedita como botón cuadrado de 40–44 dp con el fondo y el borde de `Panel.Button.Secondary` (`surface2`, `line2`, radio 10).
- **Vitales**: cinco **tarjetas** `Panel.Card` (fondo `surface`, borde `line`, radio 12) en una fila: **FC**, **SpO₂**, **Resp**, **Temp**, **PA**, cada una con su rótulo `Panel.CardLabel` en el color fijo del parámetro (`@color/ecg`, `spo2`, `resp`, `temp`, `pa`), la unidad con `Panel.CardUnit` a la derecha y el número con `Panel.Value` (Barlow Semi Condensed, numerales tabulares) en el mismo color; sin dato, "—" en `@color/faint`. La PA puede ir más chica para que entre "120/80".
- **Estado**: el resto de los textos de hoy (`lastData`, `pusherStatus`, `otherStatus`, `lastPost`) en una tarjeta `Panel.Card` con el rótulo **ESTADO** en `Panel.Text.Caps` y el texto en `@color/body` 14 sp. No se pierde ningún dato que muestra hoy.
- **Botones**: los cuatro de la parte 1 pasan a los estilos del Panel Local: **USB-config**, **Testing** y **Panel Local** con `Panel.Button.Secondary` (52 dp, Barlow SemiBold 16 sp, radio 12, borde `line2`); **ONDOCTOR365** con un `Panel.Button.Primary` en violeta (`@color/ondoctor_icon`, texto blanco, Barlow Bold), mismo alto que los otros. `MainButton` y `MainButton.Primary` de la parte 1 se borran si quedan sin uso.
- **La capa de configuración, oscura**: misma tarjeta `Panel.Card`. Los pasos como pestañas con el azul `@color/primary` para el activo y `@color/muted` para el resto. Los campos con `TextInputLayout` outlined en oscuro (borde `line3`, rótulo `muted`, texto `text`, foco `primary`); Producción/Dev con el control segmentado del Panel Local (`Panel.Segments` / `Panel.Segment`) si encaja con el `ChipGroup`/`MaterialButtonToggleGroup` actual sin reescribir la lógica, si no, chips oscuros. **Siguiente**/**Guardar** con el azul `primary` y texto `onPrimary`; **Atrás** con `Panel.Button.Secondary`. Las ayudas (`Help`) en `@color/body`; el error del formulario en `@color/test_fail` (el rojo `form_error` no se lee sobre oscuro). La lista del paso Kiosco (`item_kiosk_check.xml`) con los mismos colores. Reemplazá `ThemeOverlay.BerryMed.Form` por un overlay oscuro (por ejemplo `ThemeOverlay.BerryMed.FormDark` sobre `ThemeOverlay.Material3.Dark` con `colorPrimary` = `primary`, `colorSurface` = `surface`); borrá el claro si queda sin uso.
- Todo con los colores y estilos que ya existen en `values/colors.xml` y `values/themes.xml`; si falta uno, agregarlo con nombre del handoff y comentario corto, como los demás.

### Verificación en el emulador

1. Pantalla principal recién instalada (sin configuración): la capa de configuración aparece abierta sola. Captura.
2. Con configuración guardada: se ve la cabecera, las cinco tarjetas, Estado y los cuatro botones, con la capa cerrada. Captura en horizontal y otra en vertical.
3. USB-config abre la capa; ← y el gesto de atrás la cierran; recorrer los cuatro pasos y Guardar funciona igual que antes (los tests de la configuración en verde). Captura de cada paso.
4. La ruedita: Pedir permiso USB y Evitar que se duerma hacen lo de antes.
5. Kiosco sin URL → "Abrir la configuración" abre la capa.

### Documentación

README: la pantalla principal (cabecera, tarjetas, la configuración detrás de **USB-config**, **Pedir permiso USB** y **Evitar que se duerma** en la ruedita) y los pasos de instalación que dicen "en la pantalla principal, completá…". `docs/decisiones.md`: una entrada.

## Parte 3: un solo estilo de botón, cuadrado, y Finalizar en rojo (pedido del usuario, 2 nov 2026)

El usuario pidió que en el Panel Local el botón **Finalizar** de arriba a la derecha vaya **en rojo**, y que los botones que hoy parecen badges (los `Panel.Button.Outline` y compañía, con radios de 10–12 dp) pasen a **un único estilo de botón, cuadradito**. Vale para el Panel Local y para la pantalla principal de la parte 2, que usa los mismos estilos. Misma rama, commits aparte.

- **Una sola forma**: todos los botones (`Panel.Button.Outline`, `.Secondary`, `.Card`, `.Light`, `.Primary` y los de la pantalla principal) heredan de `Panel.Button` con **esquinas de 6 dp**, Barlow SemiBold 16 sp, borde de 1 dp y el mismo ripple. Las variantes cambian **sólo el color** (y el alto donde hace falta), no la forma:
  - **Secundario** (el de casi todos): fondo `surface2`, borde `line3`, texto `text`.
  - **Primario** (Escuchar, Siguiente/Guardar, etc.): fondo `primary`, texto `onPrimary`, sin borde.
  - **Peligro** (nuevo, `Panel.Button.Danger`): fondo `@color/alarm` (`#FF4D4F`), texto blanco, sin borde; apretado, un rojo más oscuro (agregá el color con nombre y comentario, por ejemplo `alarmPressed`). Lo usan **Finalizar** de la cabecera y el botón que confirma en el diálogo de Finalizar (hoy `Panel.Button.Light` "Finalizar sesión"), para que la acción se lea igual en los dos lugares. Cancelar del diálogo queda secundario.
  - **ONDOCTOR365** (pantalla principal): fondo `ondoctor_icon`, texto blanco.
- **Altos**: 48 dp por defecto; 44 dp en la cabecera del Panel Local (Finalizar, Congelar y los de los paneles de dispositivo) para no agrandarla; los primarios grandes que hoy miden 68 dp mantienen el alto pero toman la misma forma. Ningún botón cambia de lugar ni de texto.
- **Los segmentos** (`panel_segment.xml`, `Panel.Segments`: II/3/7, 12,5/25/50, 5/10/20, filtros del estetoscopio) pasan de 8 a 6 dp de radio, el contenedor también, para que combinen.
- **Lo que no es botón sigue redondo**: los chips de estado de la cabecera (`Panel.Chip`), los badges (`Panel.Badge`) y el toast quedan como píldoras. Así se distingue de un vistazo qué se toca (cuadrado) y qué sólo informa (redondo). Decilo en el KDoc o en un comentario de `themes.xml`.
- Borrá los estilos que queden sin uso y los selectores de color de `res/color/` que ya no usa nadie.
- **Verificación**: capturas del Panel Local (ECG, un dispositivo abierto, el diálogo de Finalizar) y de la pantalla principal, en `docs/capturas/plan-11/`. Los tests en verde. `docs/decisiones.md`: una línea con la regla cuadrado = se toca, redondo = informa.
