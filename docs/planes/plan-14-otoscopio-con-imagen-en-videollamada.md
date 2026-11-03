# Plan 14: el otoscopio manda imagen en la videollamada

Plan para un agente (3 nov 2026). Reporte de campo con la 1.3.6 en la tablet real. En una consulta, Testing → Teleconsulta dice **Sala conectada**, **Publica: otoscopio por UVC** y **0.0 cuadros**. El médico recibe la pista, pero sin imagen. En el Panel Local el otoscopio sí se ve. El permiso USB del otoscopio ya está aceptado con «Usar de forma predeterminada».

## Causa (verificada en la librería)

`otoscope/UvcOtoscopeFrames.kt` abre el otoscopio por libuvc **sin vista**: pone `setFrameCallback(…, PIXEL_FORMAT_NV21)` y llama a `startPreview()` sin superficie. El KDoc supone que libuvc "sólo convierte y llama al callback".

Eso no es así. En `libUVCCamera.so` de `com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.7` está el mensaje `UVCCamera::window does not exist/already running/could not create thread etc.`: `UVCPreview::startPreview` sólo crea el hilo de captura si hay `mPreviewWindow`. Sin superficie no se lee la cámara, el callback nunca corre y llegan 0 cuadros. En el Panel Local hay un `TextureView`, por eso anda.

## Cambios

1. **Superficie para libuvc en la teleconsulta** (`UvcOtoscopeFrames`):
   - Antes de `startPreview()`, darle a la cámara una superficie que nadie ve y que **se consuma** para que el productor nunca se trabe. Lo recomendado es un `ImageReader` del tamaño abierto, en el formato que pide libuvc para la ventana (mirá qué usa: RGBX_8888 o RGBA_8888; probá `PixelFormat.RGBA_8888` o `RGBX_8888`), con `maxImages` 2.
   - El reader tiene un `OnImageAvailableListener` en un `HandlerThread` propio que hace `acquireLatestImage()?.close()`.
   - Usá el método que tenga `UVCCamera` 3.2.7 para eso (`setPreviewDisplay(Surface)` o `setPreviewTexture`). Verificá la firma con `javap` sobre el `.aar` del caché de Gradle; no la adivines.
   - El callback NV21 sigue igual.
   - Al soltar (`release`): primero `setFrameCallback(null)`, después `stopPreview`/cerrar la cámara, después cerrar el `ImageReader` y el `HandlerThread`, en ese orden y sin excepciones que escapen.
   - Corregí el KDoc.
   - Si `ImageReader` no sirve con la ventana de libuvc (el log lo diría), la alternativa es un `SurfaceTexture` desacoplado con un consumidor que lo vacíe. Explicá en el PR cuál quedó y por qué.
2. **No publicar una pista vacía** (`tele/LiveKitBackend.publishOtoscope`):
   - Después de abrir la fuente, esperar el **primer cuadro** hasta 5 s antes de `publishVideoTrack`.
   - Si no llega, soltar todo y devolver "El otoscopio abrió pero no manda imagen", con una línea `[TELE]` en el log.
   - Vale para los dos caminos (UVC y Camera2).
   - Si la regla se puede sacar a algo sin Android, con test.
3. **Testing no se pelea con el reposo** (`TotemRuntime`):
   - Mientras la pantalla Testing está abierta (`TestingActivity.onStart`…`onStop`, no sólo con la prueba iniciada), `applyHubRest` no apaga ni enciende puertos. Sólo vale el encendido del termómetro, como con `hubRestBlocked`.
   - Al cerrar Testing, `hubRest.again()` para que se aplique lo que corresponda.
   - Así, un **Encender** a mano en Testing deja el puerto encendido mientras se está ahí. Antes, el reposo lo apagaba y el pedido de permiso del otoscopio aparecía y se iba.
   - Ajustá el texto de ayuda del hub en Testing.
4. **El punto del permiso del otoscopio** en el paso Kiosco (`ui/KioskChecklist.kt`, `MainActivity.renderChecklist`):
   - **verde** si el otoscopio está enchufado y `hasPermission`;
   - **ámbar** si está enchufado sin permiso;
   - **gris** si no está (puerto apagado).
   - El texto dice cuál de los tres es. Con test en `KioskChecklistTest`, si existe.

## Tests y entrega

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.
- El emulador `berry_tablet` (`emulator-5554`) está prendido. Instalá el APK y verificá que nada se rompe: Testing, paso Kiosco con el punto gris sin otoscopio, kiosco. El emulador no tiene otoscopio UVC: lo de la imagen se confirma en la tablet; decilo.
- README (Teleconsulta: 30 cuadros/s esperables y el mensaje nuevo; Testing y el hub), `docs/decisiones.md` (causa y arreglo) y `docs/hardware.md` (la superficie que exige libuvc).
- Rama `fix/otoscopio-imagen-videollamada` desde `main`; PR contra `main`. Commits en español con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commitear este plan.
- **No cambiar la versión**: la 1.3.7 la arma la sesión principal.
