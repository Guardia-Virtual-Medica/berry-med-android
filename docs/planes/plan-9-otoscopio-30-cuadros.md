# Plan 9: el otoscopio a 30 cuadros por segundo

Plan para un agente (2 nov 2026). Sale de un reporte de campo con la 1.3.2: en el Panel Local la imagen del otoscopio "sale bastante trabada". Sin cambios en la API ni en el kiosco (`gvm.mt.totem`). Todo es en `app/src/main/java/com/guardiavirtual/berrymed/otoscope/` y en la tarjeta del otoscopio del Panel Local.

## Qué pasa hoy

- El otoscopio (`349C:3301`) ofrece MJPEG cuadrado a **30 o 5 fps** por cada tamaño (`docs/hardware.md`). La tablet real lo abre **por UVC** (libuvc, módulo de AndroidUSBCamera 3.2.7), no por Camera2.
- `UvcHandle.open` pide `setPreviewSize(ancho, alto, MIN_FPS = 1, MAX_FPS = 30, MJPEG, DEFAULT_BANDWIDTH)`. En libuvc (`_uvc_get_stream_ctrl_format()` de `stream.c`) eso significa: recorrer los intervalos que declara la cámara para ese tamaño **en el orden del descriptor** y quedarse con **el primero** cuya cadencia caiga entre `min_fps` y `max_fps`, ambos inclusive. Con el rango 1 a 30, tanto 5 como 30 califican: si la cámara declara primero el intervalo de 5 fps, la app abre a 5 fps y la imagen se ve a saltos. Es la causa más probable del reporte y la única que se arregla desde el código sin tocar el hardware.
- Si ningún intervalo cae en el rango, la librería tira `IllegalArgumentException`; hoy el `catch` vuelve a pedir el tamaño sin rango, que por dentro usa 1 a 31 (`DEFAULT_PREVIEW_MIN_FPS`/`MAX_FPS`): mismo problema.
- Por Camera2/CameraX no se pide ninguna cadencia: `Preview` e `ImageAnalysis` van con el rango que elija el HAL.
- Nadie mide ni muestra los cuadros por segundo reales del Panel Local. Testing → Teleconsulta sí los muestra para la publicación en LiveKit (`framesPerSecond`), y eso queda como está.

## Decisiones

- **Pedir 30 fps exactos** por UVC, y sólo si la cámara no los ofrece para ese tamaño, aceptar lo que haya (como hoy). Nunca dejar que un rango ancho elija por nosotros.
- **Decir la cadencia pedida en el log** y **mostrar los cuadros por segundo reales** en la tarjeta del Panel Local, para que el próximo reporte de campo venga con el número: si la tarjeta dice 30 y aun así se ve trabada, el problema está en otro lado (ancho de banda del hub con el estetoscopio escuchando, decodificación, la vista).
- Por Camera2 pedir también 30 fps si la cámara lo ofrece, con `Camera2Interop`. Es el camino que la tablet real no usa: va segundo y no se bloquea la entrega por él.
- No se cambia la resolución (800×800) ni el `bandwidth` de libuvc: a 800² y 30 fps MJPEG el USB 2.0 alcanza; subir la resolución es lo que se come el ancho de banda que comparte con la plaqueta.

## Diseño

### Parte 1: UVC a 30 (`otoscope/UvcHandle.kt`)

1. Constante `FPS = 30` (sacar `MIN_FPS`/`MAX_FPS`). Primer intento: `setPreviewSize(w, h, FPS, FPS, FRAME_FORMAT_MJPEG, DEFAULT_BANDWIDTH)`. Si tira `IllegalArgumentException`, segundo intento con el rango de hoy (`1` a `FPS`), y si también tira, el de hoy sin rango. Guardar en el handle qué quedó: `val fps: Int?` (30 si entró el primer intento, null si la cadencia la eligió la cámara).
2. Función pura, sin Android, para probar la decisión sola: por ejemplo `OtoscopeResolution.fpsAttempts(): List<IntRange>` = `[30..30, 1..30]` (o un objeto `OtoscopeFps` al lado, lo que quede más claro), y `UvcHandle` la recorre en orden. Lo que importa es que la regla "primero exactos, después el rango" esté en un lugar sin Android.
3. Las dos líneas de log que hoy dicen `… a 800×800 MJPEG` (`[OTOSCOPIO]` en `UvcOtoscope` y `[TELE]` en `UvcOtoscopeFrames`) pasan a decir también la cadencia: `a 800×800 MJPEG, 30 cuadros/s` o `a 800×800 MJPEG, cadencia a elección de la cámara`.

### Parte 2: cuadros por segundo reales en la tarjeta (`UvcOtoscope.kt`, `LocalPanelActivity.kt`)

1. `OtoscopeSource` suma un callback opcional de cadencia: por ejemplo `fun open(done: (String?) -> Unit, onRate: ((Double) -> Unit)? = null)` o un `var onRate: ((Double) -> Unit)?`; elegir lo que menos toque a `CameraXOtoscope`.
2. En `UvcOtoscope`, `onSurfaceTextureUpdated` (hoy vacío) es un cuadro dibujado: contar y, una vez por segundo (con `System.nanoTime()`, en el hilo de la pantalla, que es donde llega), avisar la cadencia. En `CameraXOtoscope` lo mismo con `PreviewView`, si tiene algo equivalente (`previewView.previewStreamState` no da cuadros; si no hay forma barata, por Camera2 no se muestra el número y se dice en el KDoc).
3. En la tarjeta, el encabezado que hoy dice `En vivo · UVC` (`R.string.local_otoscope_ready` con `source.label`) pasa a decir `En vivo · UVC · 29 cuadros/s`, redondeado, actualizado una vez por segundo. Strings nuevos en `values/strings.xml`, mismo tono que el resto. Sin cadencia todavía (primer segundo) se muestra como hoy.
4. Una línea de log `[OTOSCOPIO] … cuadros/s` sólo si la cadencia cae por debajo de 20 durante más de 5 s seguidos (no una por segundo: el log se llena). Dice el número.

### Parte 3: Camera2 a 30 (`otoscope/ExternalCamera.kt`, `CameraXOtoscope.kt`, `CameraXOtoscopeFrames.kt`)

1. Al armar el caso de uso, con `Camera2Interop.Extender` (`androidx.camera.camera2.interop`, `@OptIn(ExperimentalCamera2Interop::class)`) poner `CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE` en `Range(30, 30)` **sólo si** `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` de la cámara externa (por `Camera2CameraInfo.from(cameraInfo).getCameraCharacteristic`) incluye ese rango; si no, el rango disponible con el límite inferior más alto que llegue a 30; si ninguno llega, no tocar nada. La elección del rango es otra función pura (`List<Pair<Int, Int>>` → `Pair<Int, Int>?`) con test.
2. Como `ExternalCamera.bind` recibe el `useCase` ya armado, la característica se lee antes, con el `ProcessCameraProvider` ya en mano (`provider.availableCameraInfos` filtrado por `selector`), o se pasa el `cameraInfo` a quien arma el caso de uso. Lo que menos reordene `bind`.

## Tests

- La lista de intentos de cadencia: primero 30 exactos, después 1 a 30 (`OtoscopeResolutionTest.kt` o `OtoscopeFpsTest.kt` nuevo, `kotlin.test`).
- La elección del rango de Camera2: con `(30,30)` disponible lo elige; con `(15,30)` y `(5,5)` elige `(15,30)`; con sólo `(5,5)` da null.
- El contador de cadencia, si se separa sin Android (marcas de tiempo → cadencia por segundo), con un test de tres segundos de cuadros.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.

## Verificación

El emulador `berry_tablet` (`emulator-5554`, adb en `~/Library/Android/sdk/platform-tools/adb`) no tiene otoscopio ni hub: ahí sólo se verifica que compila, que el Panel Local abre y que la tarjeta del otoscopio sigue diciendo que no aparece. **Decirlo, no darlo por probado.** La prueba real es en la tablet: Panel Local → Encender el otoscopio, leer `cuadros/s` en la tarjeta y la línea `[OTOSCOPIO] Imagen por UVC a 800×800 MJPEG, 30 cuadros/s` en el log; después, con el estetoscopio escuchando al mismo tiempo, ver si la cadencia baja (es el riesgo "ancho de banda isócrono con otoscopio y audio a la vez" de `docs/hardware.md`).

## Documentación

- README, sección del Panel Local (otoscopio): que pide 30 cuadros y muestra los cuadros por segundo en la tarjeta; y en la tabla de problemas, la línea del otoscopio trabado: qué mirar (la cadencia en la tarjeta y la línea del log).
- `docs/hardware.md`, otoscopio: cómo elige libuvc el intervalo (el primero del descriptor dentro del rango) y por qué la app pide 30 exactos. Marcar en "Riesgos a confirmar en la tablet real" el de la cadencia con el estetoscopio escuchando.
- `docs/decisiones.md`: entrada con estas decisiones.
- Commitear este plan.

## Entrega

Rama `feat/otoscopio-30-cuadros` desde `main`, commits en español en el estilo del repo (mensaje largo que cuenta qué y por qué), PR contra `main`. No cambiar la versión (`versionCode`/`versionName`): la decide el usuario al armar la release. No tocar `local.properties`, `docs/capturas/` ni `docs/apk/`.
