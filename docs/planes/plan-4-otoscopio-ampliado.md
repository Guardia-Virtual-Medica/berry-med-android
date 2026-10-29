# Plan 4: otoscopio ampliado en el Panel Local

Plan escrito para que lo ejecute un agente. Hecho: ver `docs/decisiones.md`.

## Qué es

En el Panel Local (ver `README.md`, sección "Panel Local") la tarjeta del otoscopio muestra la imagen en un cuadro de 220 dp y a veces es chica para mirar un oído. Hay que poder **ampliarla**: la imagen pasa al centro de la pantalla, grande, y lo que queda atrás (el resto del panel) se ve desenfocado y oscurecido. Se vuelve a la tarjeta con un botón o con el gesto de atrás. Nada se guarda.

De paso, un arreglo chico en la misma pantalla: en 1280×800 dp el botón **Escuchar** de la tarjeta del estetoscopio se parte en dos líneas porque los tres botones de esa fila no entran.

## Cómo está hoy

- `app/src/main/res/layout/activity_local_panel.xml`: la raíz es un `FrameLayout`. Adentro, el `LinearLayout` `@+id/panel` con las tres columnas (ECG, signos, y la columna derecha que es un `ScrollView` con cámara, otoscopio, presión y estetoscopio) y, último hijo, el candado `@+id/lock` que tapa todo hasta el PIN.
- La tarjeta `@+id/otoscopeCard` tiene un `FrameLayout` cuadrado de 220 dp (sin id) con tres vistas superpuestas: `otoscopePreview` (`PreviewView` de CameraX, camino Camera2), `otoscopeTexture` (`TextureView`, camino UVC) y `otoscopeFrozen` (`ImageView` para Congelar). Debajo, `otoscopePowerButton` (Encender/Apagar), `otoscopeFreezeButton` (Congelar/Seguir) y `otoscopeMessage`.
- `ui/LocalPanelActivity.kt`: `otoscope: OtoscopeSource?` es la imagen abierta; `otoscopeFreeze: FreezeFrame` congela; `render()` cada 500 ms pone el texto de los botones según `LocalSnapshot.otoscopeState`; `releaseOtoscope()` suelta la cámara (desde Apagar, `onPause` si termina, `onStop`); `lock()` vuelve a pedir el PIN.
- `otoscope/CameraXOtoscope.kt` dibuja en el `PreviewView`; `otoscope/UvcOtoscope.kt` toma el `SurfaceTexture` del `TextureView` (`open()` espera `onSurfaceTextureAvailable` si todavía no está) y le pasa un `Surface` a libuvc.
- La app es minSdk 26, targetSdk 35. La tablet del tótem tiene Android reciente.

## Diseño

### El escenario del otoscopio

Darle un id al `FrameLayout` cuadrado de la tarjeta: `@+id/otoscopeStage`. Es la única vista que cambia de lugar: **se reparenta**. En modo normal vive en la tarjeta; en modo ampliado se saca de la tarjeta y se mete en una capa nueva de la raíz, `@+id/otoscopeZoom`, y al cerrar vuelve a la tarjeta en la misma posición (índice 1, después del título). Así la cámara sigue dibujando en la misma vista y Congelar funciona igual en los dos modos (el `ImageView` congelado viaja con el escenario). Mientras está ampliado, en la tarjeta queda un hueco del mismo tamaño con el texto "Ampliado" (un `TextView` `@+id/otoscopePlaceholder`, 220 dp, `GONE` normalmente).

Tamaño del escenario ampliado: el cuadrado más grande que entra dejando 24 dp de margen por lado y lugar para una fila de botones abajo (en 1280×800 dp queda de unos 680 dp). Extraer el cálculo a una función pura `ui/panel/OtoscopeZoom.kt` (`fun stageSide(widthPx: Int, heightPx: Int, marginPx: Int, buttonsPx: Int): Int`) para testearla.

### La capa ampliada

`@+id/otoscopeZoom`: un `FrameLayout` hijo de la raíz, **antes** de `@+id/lock` en el XML (el candado tiene que seguir tapando todo), `match_parent`, `GONE` por defecto, `clickable="true"` para que los toques no pasen al panel de atrás. Contiene:

- un fondo oscurecido (su `background` es un color con alfa, por ejemplo `#CC0B1A2B`);
- en el centro, un contenedor vertical con el escenario (el `otoscopeStage` reparentado, `layout_width/height` = lado calculado) y debajo una fila de botones: **Congelar / Seguir**, **Apagar** y **Reducir**;
- una línea de mensaje `@+id/otoscopeZoomMessage` (el mismo texto que `otoscopeMessage`; actualizar los dos a la vez, extraé un `setOtoscopeMessage(text)`).

**Desenfoque de lo de atrás**: en Android 12 o más (`Build.VERSION.SDK_INT >= 31`) aplicar `panel.setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP))` sobre `@+id/panel` al abrir, y `panel.setRenderEffect(null)` al cerrar. En versiones anteriores queda sólo el oscurecido. Nota: la cámara de la tablet (`cameraPreview`) y el papel del ECG quedan debajo del desenfoque; está bien, siguen andando.

### Cómo se abre y se cierra

- Se abre con un botón nuevo en la tarjeta, **Ampliar** (`@+id/otoscopeZoomButton`, al lado de Congelar; si no entran tres botones en la fila, poné Ampliar como botón ancho en una segunda fila), y también **tocando la imagen** del escenario cuando está en la tarjeta. Sólo se habilita cuando el otoscopio está encendido (`otoscope != null && otoscopeState == ON`), igual que Congelar.
- Se cierra con **Reducir**, con el gesto de atrás (registrá un `OnBackPressedCallback` que esté habilitado sólo mientras está ampliado; el gesto de atrás con el panel en modo normal sigue haciendo lo de hoy) y solo, cuando el otoscopio se apaga por cualquier camino (`releaseOtoscope()` cierra el zoom primero) y cuando el panel se bloquea (`lock()`).
- Los botones de la capa ampliada hacen lo mismo que los de la tarjeta: Congelar/Seguir usa `otoscopeFreeze` (el mismo `FreezeFrame`; hay que pasarle también el segundo botón y el segundo mensaje, o actualizar los textos de los dos desde un solo lugar; elegí lo más simple y no dupliques estado), Apagar llama a `otoscopeOff(...)` que ya cierra el zoom por `releaseOtoscope()`.
- Sin animación: cambia de un cuadro al otro. Si querés, un `TransitionManager.beginDelayedTransition` de la raíz con `ChangeBounds` corto, pero no es obligatorio y no tiene que complicar el reparenting.

### El camino UVC al reparentar

Al sacar un `TextureView` de la ventana, Android destruye su `SurfaceTexture` (`onSurfaceTextureDestroyed`) y al volver a ponerlo crea otro (`onSurfaceTextureAvailable`). libuvc ya está dibujando en el `Surface` del primero, así que la imagen se cortaría. La solución estándar:

- `UvcOtoscope` guarda el `SurfaceTexture` que usó en `openCamera` y deja puesto un `SurfaceTextureListener` mientras está abierto, que:
  - en `onSurfaceTextureDestroyed` devuelve **false** (la vista no lo libera; lo libera `release()` de `UvcOtoscope`, como hoy con el `Surface`);
  - en `onSurfaceTextureAvailable(nuevo)`, si el guardado sigue vivo y es distinto del nuevo, llama `textureView.setSurfaceTexture(guardado)` (y libera el nuevo). Con eso el `TextureView` reparentado sigue mostrando el mismo stream sin tocar libuvc.
- `close()` saca el listener, como hoy.
- El `PreviewView` de CameraX se banca el reparenting solo: CameraX vuelve a pedir la superficie por el `SurfaceProvider` y sigue. Verificá en el emulador que la cámara de la tablet (la misma clase de vista) sobrevive a un reparent si querés comprobarlo, pero el otoscopio por Camera2 no se puede probar sin hardware.
- `snapshot()` (para Congelar) sigue siendo `textureView.bitmap` / `previewView.bitmap`.

### El botón Escuchar

En la tarjeta del estetoscopio, la fila `stethoListenButton` / `stethoStopButton` / `stethoGainButton`: que entren en una línea en 1280×800 dp. Probá primero con `android:paddingStart/End="8dp"` e `android:insetLeft/Right="0dp"` en los tres y el texto de la ganancia como "x1" / "x2" / "x4" con `contentDescription` "Ganancia"; si aun así se corta, dejá Escuchar y Parar en la fila y la ganancia en una segunda fila a la derecha. Verificalo con una captura del emulador `berry_tablet` (2560×1600 a 320 dpi = 1280×800 dp): el emulador está corriendo como `emulator-5554`, `adb` en `~/Library/Android/sdk/platform-tools/adb`. Para ver las tarjetas sin hardware hay que forzarlas temporalmente (`stethoAvailable = true`, `otoscopeAvailable = true` en `TotemRuntime.localSnapshot()`); eso **no** se commitea.

## Tests (JUnit, sin Android)

- `OtoscopeZoom.stageSide`: en 2560×1600 con margen y botones deja un cuadrado que entra en alto; en una pantalla más ancha que alta manda el alto; en una más alta que ancha manda el ancho; nunca negativo.
- Los tests existentes siguen pasando (`./gradlew test`, hoy 130).

## Criterios de aceptación

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.
- Ampliar, Congelar en ampliado, Reducir, Apagar desde ampliado, gesto de atrás en ampliado, y bloquear el panel con el zoom abierto: ninguno deja el escenario fuera de la tarjeta ni el desenfoque puesto. Después de Reducir, el escenario vuelve a la tarjeta en su lugar y con su tamaño de 220 dp.
- Con el zoom abierto, el candado (`lock`) sigue tapando todo si se bloquea.
- Captura del emulador con las tarjetas forzadas: el panel normal con Ampliar visible, y el modo ampliado (con el escenario centrado y el fondo desenfocado y oscurecido; sin hardware el escenario se ve negro, alcanza). Dejá las capturas en `docs/capturas/` (no se commitean).
- README, sección "Panel Local", párrafo del otoscopio: una oración sobre Ampliar y Reducir.

## Entrega

Rama `feat/otoscopio-ampliado` desde `main`, commits en español en el estilo del repo (`git log --oneline -5`) con `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`, `git add` con rutas explícitas (sin `docs/`), PR contra `main` con "Qué trae", "Qué no hace" y "Cómo se probó", y el pie `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. No mergear. Decir en el PR que el reparenting del camino UVC no se probó con el otoscopio real y qué mirar en la tablet: que al ampliar la imagen por UVC no se corte, y que al reducir siga.
