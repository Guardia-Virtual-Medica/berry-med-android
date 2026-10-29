# Plan 3: otoscopio en el Panel Local

Plan escrito para que lo ejecute un agente. Hecho: ver `docs/decisiones.md`.

## Qué es

Agregar al Panel Local (ver `README.md`, sección "Panel Local", y `docs/planes/plan-1-panel-local.md`) una tarjeta de **otoscopio**: encenderlo, ver su imagen en vivo, congelarla y apagarlo. Nada se guarda.

## El hardware, ya definido

- **Otoscopio**: una cámara USB estándar (UVC) `349C:3301`, "HD video". Manda MJPEG en cuadrado: 480², 800², 960², 1280² y 1440², a 30 o 5 fps. Trae además un micrófono USB (se ignora) y una interfaz de almacenamiento (se ignora). Cuelga del **puerto 1 del hub YKUSH3**; `BerryConfig.ykushOtoscopePort` dice cuál (por defecto 1).
- Encender el puerto es enchufar el otoscopio para Android, y prende su luz; apagarlo lo desenchufa y apaga la luz.
- **Resolución**: 800×800. A 1440² la cámara reserva casi todo el ancho de banda del USB 2.0 y deja sin lugar a la plaqueta.

## Los dos caminos para el video

Android puede mostrar una cámara USB de dos maneras, y cuál sirve depende del fabricante de la tablet. No cambia el armado ni la tarjeta: cambia sólo el código que abre la cámara.

- **Camino A, Camera2/CameraX.** Si la tablet lista el otoscopio como cámara externa (`LENS_FACING_EXTERNAL`), se abre con CameraX igual que la cámara de la tablet, que ya está en el panel. Testing → "Dispositivos que ve Android" → Cámaras muestra "externa (USB)" cuando es así.
- **Camino B, libuvc.** Si no aparece, hace falta una librería que hable UVC directamente sobre `UsbDeviceConnection`: `com.github.jiangdongguo:AndroidUSBCamera` (AUSBC) o el `UVCCamera` de saki4510t. Requiere el permiso USB del dispositivo, así que en ese caso el otoscopio **sí** se agrega a `usb_device_filter.xml` (`vendor-id="13468" product-id="13057"`) y a `UsbHub.totemDevices()`.

**Qué hacer en este plan: los dos caminos, completos.** El usuario quiere probar el otoscopio en la tablet sin otra vuelta de código, así que la tarjeta tiene que funcionar sea cual sea el caso. Detrás de una interfaz chica (`OtoscopeSource` con `open(...)`, `close()`, y un `isAvailable()`), dos implementaciones:

- `CameraXOtoscope`: busca la cámara `LENS_FACING_EXTERNAL`, la abre con CameraX en el `PreviewView` de la tarjeta, pide 800×800 con un `ResolutionSelector` y acepta la más cercana.
- `UvcOtoscope`: con la librería AUSBC (`com.github.jiangdongguo:AndroidUSBCamera:3.3.3`, está en JitPack, que el proyecto ya tiene como repositorio; si esa versión no resuelve, usá la última estable que encuentres y anotalo), abre el dispositivo `349C:3301` por `UsbDeviceConnection`, elige MJPEG 800×800 a 30 fps y renderiza en la vista que la librería pida (un `TextureView` o su propia vista) dentro de la tarjeta. Ignora el micrófono y el almacenamiento del dispositivo.

La elección es automática al encender: después de prender el puerto, se espera hasta 8 s a que aparezca **o** una cámara externa en `CameraManager` **o** el dispositivo USB `349C:3301` con permiso; lo que aparezca primero decide el camino, y la tarjeta dice cuál usó ("por Camera2" / "por UVC"). Para el camino B hace falta el permiso USB: agregá el otoscopio a `usb_device_filter.xml` (`vendor-id="13468" product-id="13057"`) y a `UsbHub.totemDevices()`, y actualizá el comentario del filtro y el README que hoy dicen que no se agrega.

## Diseño

### Encender y apagar

Una secuencia en el runtime (`otoscopeOn()` / `otoscopeOff()`), siempre en un hilo de trabajo porque el YKUSH3 bloquea hasta 1 s:

1. **Encender**: `ykush.setPort(puerto del otoscopio, on)`; esperar hasta 8 s a que la cámara aparezca (`CameraManager.registerAvailabilityCallback` o sondeo de `cameraIdList` cada 250 ms buscando una con `LENS_FACING_EXTERNAL`); abrirla en el `PreviewView` de la tarjeta. Si no hay YKUSH3 enchufado, saltear el hub e intentar directo. Si a los 8 s no apareció: mensaje "El otoscopio no aparece como cámara" y apagar el puerto.
2. **Apagar**: cerrar la cámara, después `setPort(..., off)`.
3. **Al cerrar el panel**: si el otoscopio quedó encendido, apagarlo (cámara y puerto). `stopLocal()` ya es el lugar.
4. **Al arrancar el servicio**: no tocar el puerto. Al encender la tablet el hub arranca con todo prendido y eso lo decide Mauricio desde el armado, no la app.

Extraer la máquina de estados a una clase pura (`apagado → encendiendo puerto → esperando cámara → encendido → apagando`), con los tiempos de espera como parámetros, para testearla sin Android.

### Las dos cámaras a la vez

Camera2 no garantiza abrir la cámara de la tablet y la externa al mismo tiempo. Mientras el otoscopio está encendido, la vista previa de la cámara de la tablet se suelta (`unbindAll`) y la tarjeta de cámara dice "En pausa mientras el otoscopio está encendido"; al apagar el otoscopio vuelve sola. Un solo `ProcessCameraProvider` para las dos.

### La tarjeta

En la columna derecha de `activity_local_panel.xml`, encima o debajo de la cámara según lo que entre en 1280×800 dp (si no entra, la columna derecha pasa a un `ScrollView`): título "Otoscopio", `PreviewView` cuadrado de ~220 dp con un `ImageView` superpuesto para congelar, botones **Encender** / **Apagar**, **Congelar** / **Seguir** (reusar el mismo mecanismo de `freeze()` / `unfreeze()` que la cámara, extraído a un helper para no duplicarlo), y una línea de mensaje ("Encendiendo…", "Esperando la cámara…", "Otoscopio encendido", "Sin YKUSH3: se intenta directo", errores). La tarjeta se oculta si no hay YKUSH3 ni otoscopio enchufados.

## Tests (JUnit, sin Android)

- La máquina de estados: encender pasa por los estados en orden; si la cámara no aparece en el plazo vuelve a apagado y pide apagar el puerto; apagar desde cualquier estado termina en apagado; cerrar el panel con el otoscopio encendido produce "cerrar cámara, apagar puerto"; sin YKUSH3 se saltea el hub.
- Elección de resolución: entre las que ofrece la cámara, la más cercana a 800×800 cuadrada.
- Los tests existentes siguen pasando.

## Criterios de aceptación

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.
- Con el panel cerrado el otoscopio queda apagado (puerto apagado si hay YKUSH3) y la cámara de la tablet no queda abierta.
- Ninguna llamada al YKUSH3 en el hilo de la pantalla.
- README: en "Panel Local", qué hace la tarjeta, que la luz se prende con el puerto, que la cámara de la tablet se pausa mientras tanto, y que hay dos caminos (Camera2 o UVC) que se eligen solos.

## Entrega

Igual que los planes anteriores, en la **misma rama y el mismo PR que el plan 2** (`feat/estetoscopio-otoscopio`), como segundo commit: commits en español en el estilo del repo con `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`, `git add` con rutas explícitas (sin `docs/`), PR contra `main` con "Qué trae", "Qué no hace" y "Cómo se probó", y el pie `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. No mergear. Decir en el PR que no se probó con el hardware y listar qué mirar: que Testing muestre la cámara externa al encender el puerto 1, que Encender prenda la luz y muestre la imagen, Congelar, Apagar, y que la cámara de la tablet vuelva sola.
