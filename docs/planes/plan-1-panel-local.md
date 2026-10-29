# Plan 1: Panel Local con plaqueta, termómetro y cámara

Plan escrito para que lo ejecute un agente. Hecho: ver `docs/decisiones.md`.

## Qué es

Berry Med es la app de tótem de la tablet: un servicio en segundo plano lee la plaqueta PM6750 (ECG de 7 derivaciones, SpO₂, respiración, presión no invasiva, temperatura) y un termómetro infrarrojo por USB, y durante una teleconsulta manda las mediciones a la API de OnDoctor. Adelante va Chrome con el kiosk web. Leé el `README.md` entero antes de empezar: explica el servicio, el parser, Testing y las convenciones.

El **Panel Local** es una pantalla nueva para atención **presencial, sin teleconsulta**: un médico o enfermero que está al lado del paciente abre el panel en la tablet, con Chrome cerrado, y ve todos los instrumentos en vivo. No identifica al paciente, no guarda nada y no manda nada a OnDoctor. Es una cosa o la otra: con el panel abierto el servicio no envía ni atiende órdenes de Pusher.

Decisiones ya tomadas con el usuario (no las rediscutas):

- Entrada: un **segundo ícono** en la tablet, "Panel Local", que abre directo el panel. También un botón "Panel Local" en la pantalla principal.
- **PIN** al abrir y cada vez que vuelve al frente. Por defecto `1515`, configurable en la pantalla de configuración.
- **ECG con las 7 derivaciones** sobre cuadrícula de papel (1 mm fino, 5 mm grueso, medidos con la densidad real de la pantalla), con barrido tipo monitor, velocidad 25 mm/s (botón para 12,5) y ganancia de pantalla x0.5 / x1 / x2.
- Tarjetas de **frecuencia cardíaca, SpO₂ con su onda, respiración con su onda, temperatura** (la de la plaqueta y la del termómetro IR con hace cuánto llegó) y **presión** con Medir y Parar y la presión del manguito mientras infla.
- **Cámara de la tablet**: vista previa, cambio frontal/trasera y Congelar. Sólo ver, nada se guarda.
- Sólo visualización: sin alarmas, sin guardar, sin PDF (eso es una v2).
- Pantalla apaisada, inmersiva, que no se apaga.
- Las ondas se dibujan a mano con `Canvas` (250 muestras por segundo por derivación; las librerías de gráficos no llegan).

## Estado de la rama `feat/panel-local`

Hay un borrador **sin commitear y que todavía no compila**. Revisalo con `git status` y `git diff`. Es un punto de partida: corregilo o reescribilo si lo mejora, pero el diseño de abajo se mantiene.

Hecho en el borrador:

- `protocol/LiveFeed.kt`: el **segundo lector del parser**. Buffers circulares por canal (7 derivaciones, SpO₂, respiración) con un contador de muestras escritas; la pantalla guarda un cursor y pide lo nuevo (`readSince`). Lleva además el último estado de la presión (`NibpState`). El parser (`BmDataParser.live`) copia cada muestra ahí apenas la decodifica, sin tocar lo que se envía. Motivo: `takePayload()` vacía las ondas en cada envío, así que la pantalla no podía leer de ahí.
- `service/TotemRuntime.kt`: `localMode` con `startLocal()` (devuelve el motivo si hay una consulta enviando; si Testing estaba abierto lo cierra), `stopLocal()` (para una presión que quedó midiendo), `localNibpStart()`, `localNibpStop()`, `localSnapshot()`. Con `localMode` no sale el POST de prueba cada 10 s ni se atienden `start-monitoring`, `stop-monitoring` ni `start-blood-pressure` (se loguea que se ignoran). La notificación dice "Panel Local abierto". `service/LocalSnapshot.kt` es lo que muestra la pantalla aparte de las ondas.
- `config/BerryConfig.kt`: `localPanelPin` (por defecto `DEFAULT_LOCAL_PIN = "1515"`) y `pinAccepted(entered, configured)`; `ConfigStore` lo guarda; `MainActivity` lo carga y lo lee del formulario (`binding.localPin`), y `activity_main.xml` tiene el campo bajo un título "Panel Local" en el paso Plaqueta. `MainActivity` también tiene `binding.localPanelButton` que abre `LocalPanelActivity`.
- `ui/panel/SweepTrace.kt`: la lógica del barrido sin Android (buffer de pantalla, cursor, hueco adelante del cursor, segmentos para `drawLines`, rango para autoescala, `samplesPerScreen(width, pxPerMm, mmPerSecond, sampleRate)`).
- `ui/panel/EcgPaperView.kt`: las 7 derivaciones sobre papel. Escala vertical: 10 mm por mV y `UNITS_PER_MV = 40` como estimación (la plaqueta manda bytes 0 a 255 con base en 128 y no documenta los mV); `displayGain` la corrige.
- `ui/panel/WaveformView.kt`: una onda con autoescala para SpO₂ y respiración; cuántas muestras entran se fija a mano porque la plaqueta no documenta la frecuencia de esas ondas.
- `app/build.gradle.kts`: CameraX 1.4.1 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`).
- `AndroidManifest.xml`: permiso `CAMERA`, `uses-feature camera.any` opcional, y `LocalPanelActivity` declarada como segundo launcher (`exported`, `label="@string/local_panel"`, `screenOrientation="landscape"`, `launchMode="singleTask"`, `taskAffinity` propia).

Falta (por eso no compila):

- `ui/LocalPanelActivity.kt`.
- `res/layout/activity_local_panel.xml`.
- Strings: `local_panel`, `section_local`, `help_local`, `hint_local_pin`, y los de la pantalla (`local_enter`, `local_pin_wrong`, `local_exit`, `local_measure`, `local_stop`, `local_camera_switch`, `local_freeze`, `local_resume`, `local_camera_denied`, lo que haga falta).
- Tests, README, build en verde, PR.

## Diseño de la pantalla

`LocalPanelActivity` (paquete `ui`), apaisada, inmersiva (`WindowInsetsControllerCompat`, ocultar barras), `FLAG_KEEP_SCREEN_ON`. Al crearse arranca el `MonitorService` con `startForegroundService` (si la tablet arrancó sin pasar por la pantalla principal, el servicio no corre).

**Candado.** Encima del panel, un overlay con el título, un campo de PIN numérico, "Entrar" y "Volver". `onStart` siempre muestra el candado (volver al frente pide el PIN de nuevo). Entrar: `pinAccepted(ingresado, runtime.config.localPanelPin)`; si no, "PIN incorrecto" y se limpia. Si el PIN es correcto, `runtime.startLocal()`; si devuelve un motivo (hay una consulta enviando), se muestra y no se abre. Si abre: se oculta el candado, arranca el ciclo de cuadros y la cámara.

**Ciclo de cuadros.** Un `Runnable` con `postOnAnimation`: en cada cuadro `tick()` del papel de ECG y de las dos ondas (traen lo nuevo del `LiveFeed` e invalidan si llegó algo); dos veces por segundo `render(runtime.localSnapshot())` para los números. Se corta en `onStop`.

**Salir.** `onPause` con `isFinishing` llama a `runtime.stopLocal()` (onStop puede tardar). `onStop`: corta el ciclo, suelta la cámara (`unbindAll`), `stopLocal()`, vuelve a bloquear.

**Layout** (`activity_local_panel.xml`), fondo `@color/monitor`, tres columnas:

1. Izquierda, ~50 %: cabecera con el título "Panel Local", una línea de estado de la plaqueta ("Plaqueta conectada · último dato hace 0,2 s" / "sin datos hace N s" / "Plaqueta sin conectar"), botones de velocidad ("25 mm/s" ↔ "12.5 mm/s"), ganancia ("x0.5" / "x1" / "x2") y "Salir". Debajo, `EcgPaperView` ocupando todo el alto.
2. Medio, ~22 %: tarjetas (fondo `@color/line`, como las de Testing): **Frecuencia cardíaca** (valor grande, debajo "Electrodos conectados / sueltos / Sin señal de ECG", "señal débil", y el código de arritmia de `ecgInfo.hrv` salvo "analizando"); **SpO₂** (valor grande con "%", el pulso "72 lpm", y `WaveformView` de ~56 dp con el canal `live.spo2`); **Respiración** (valor, "rpm", `WaveformView` ~48 dp con `live.resp`); **Temperatura** (la de la plaqueta grande, "°C", y debajo "Termómetro IR: 36,8 °C · hace 5 s" / "apretá el gatillo para medir" / "sin conectar").
3. Derecha, ~28 %: **Cámara** (`PreviewView` de CameraX, ~220 dp de alto, un `ImageView` superpuesto para la imagen congelada, botones "Cambiar cámara" y "Congelar"/"Seguir", y una línea de mensaje); **Presión arterial** (valor grande "120/80", detalle "Midiendo · manguito 80 mmHg" / "Sin medir todavía" / "Última medición terminada", botones "Medir" y "Parar" que llaman a `localNibpStart()` y `localNibpStop()` en otro hilo, y una línea de mensaje). "Medir" sólo habilitado con la plaqueta conectada y sin medición en curso; "Parar" sólo midiendo.

Los guiones que manda la plaqueta (`-`, `- -`, `- -/- -`) se muestran como "—". `spo2Pulse` viene como "98/72": partirlo en dos.

**Cámara.** Pedir el permiso en tiempo de ejecución al desbloquear; si lo niegan, un mensaje en la tarjeta y nada más. `ProcessCameraProvider`, `Preview` al `surfaceProvider` del `PreviewView`, `CameraSelector` por `lensFacing` (trasera por defecto; si no existe, decirlo). "Congelar" toma `previewView.bitmap` y lo muestra en el `ImageView`; "Seguir" lo saca. Cambiar de cámara descongela y vuelve a enlazar.

**Convivencia con Testing.** Son pantallas distintas; cada una corta su modo en `onStop`. `startLocal()` ya cierra Testing si estaba abierto.

## Tests (JUnit, sin Android, como los que ya hay)

- `LiveFeed.Channel`: `push` más allá de la capacidad descarta lo viejo; `readSince` devuelve sólo lo nuevo y avanza el cursor; con un cursor atrasado más que la capacidad arranca desde lo más viejo disponible; si pide menos de lo que hay se queda con lo más nuevo; `clear` reinicia.
- `SweepTrace`: `feed` escribe con vuelta al principio; `inGap` cubre las posiciones adelante del cursor con vuelta; `segments` no une muestras faltantes ni las del hueco; `range` con y sin datos; `samplesPerScreen` con una pantalla de 600 px, 6 px/mm, 25 mm/s y 250 Hz da 1000 muestras, y a 12,5 mm/s el doble.
- `pinAccepted`: acepta el configurado, ignora espacios, un configurado vacío vuelve a 1515, rechaza otro.
- Los helpers de formato de la pantalla si los dejás en un `companion` (guiones → "—", partir "98/72").
- Los tests existentes siguen pasando.

## Criterios de aceptación

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde. La primera vez sin `--offline`, porque CameraX se baja de Maven.
- Con el panel abierto no sale ningún POST (ni de prueba) y un `start-monitoring` se ignora y se loguea.
- El envío a la API durante una teleconsulta no cambia en nada: `takePayload()` sigue entregando lo mismo (el `LiveFeed` sólo copia).
- El README tiene una sección "Panel Local" (qué es, cómo se entra, el PIN, qué muestra, qué no hace, que la escala vertical del ECG es aproximada) y menciona el campo de PIN en Configuración.
- Sin warnings nuevos de Kotlin que sean errores reales; `scaledDensity` está deprecado, usá `TypedValue.applyDimension` o `resources.displayMetrics.density` según corresponda.

## Entrega

- Commits en español, en el estilo del repo: una oración en presente que dice qué cambia para el tótem (mirá `git log --oneline -15`), y terminar el mensaje con `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Usá `git add` con rutas explícitas: `docs/planes/` no se commitea.
- PR contra `main` con `gh pr create`, cuerpo en español con "Qué trae", "Qué no hace" y "Cómo se probó", y al final la línea `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. No lo mergees.
- Nada se probó con la plaqueta real: decilo en el PR y en el informe final, con la lista de lo que hay que mirar en la tablet (ECG en papel con electrodos puestos, presión con Medir, termómetro IR, cámara, PIN, y que con el panel abierto no salga ningún POST en la consola de Testing).
