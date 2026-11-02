# Plan 8: el kiosco se recarga solo y a pedido, sin salir del modo kiosco

Plan para un agente (2 nov 2026). Sale de un reporte de campo: la maleta prendió, Berry Med abrió el kiosco antes de que la tablet se conectara al wifi y el `WebView` quedó en "Webpage not available … net::ERR_INTERNET_DISCONNECTED". Para destrabarlo tuvieron que entrar a la configuración (cinco toques y PIN), borrarle una letra a la URL, guardar, y volver a poner la URL correcta: `KioskActivity.show()` sólo llama a `loadUrl` cuando la URL cambia, así que nada vuelve a intentar.

Sin cambios en la API ni en el kiosco (`gvm.mt.totem`). Todo es en `app/src/main/java/com/guardiavirtual/berrymed/ui/KioskActivity.kt` y alrededor.

## Estado de la rama

- Rama `fix/webview-ua-chrome`, con cambios **sin commitear** que son de otro arreglo (user agent como Chrome, `KioskUserAgent.kt` y su test, versión 1.3.1 en `build.gradle.kts`, README y `docs/decisiones.md`). **No tocarlos, no revertirlos, no commitear nada, no cambiar de rama.** El usuario decide después cómo se commitea. No cambiar la versión: la 1.3.1 ya está puesta.
- Compilar y testear con `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug`.

## Decisiones

- **Reintento automático.** Si la carga de la página principal falla (error de conexión, o HTTP 5xx del documento principal), Berry Med tapa el `WebView` con una pantalla propia (no la "Webpage not available" de Android) que dice que no se pudo abrir el kiosco y que se vuelve a intentar solo, con un botón **Reintentar**. Reintenta solo: apenas Android avisa que hay red (`ConnectivityManager.registerDefaultNetworkCallback`, `onAvailable` y también `onCapabilitiesChanged` con `NET_CAPABILITY_VALIDATED`), cada 10 s mientras siga fallando, y al volver a la pantalla (`onResume`). El reintento automático y el botón hacen `reload()` del `WebView`: vuelve a pedir la misma dirección que falló (si falló en medio del flujo, sigue ahí; no vuelve al inicio).
- **Recarga a pedido, con PIN.** El diálogo de los cinco toques pasa a tener tres botones: **Cancelar**, **Recargar** (neutral) y **Entrar** (positivo, va a la configuración como hoy). Recargar también exige el PIN correcto: un paciente no tiene que poder reiniciar el kiosco en medio de una consulta. Recargar carga **la URL configurada** (`loadUrl`, no `reload()`): es "volver a empezar", lo mismo que lograban cambiando la URL y guardando. Con PIN incorrecto no pasa nada, como hoy.
- Sin red y con la configuración abierta no cambia nada: la pantalla de "sin conexión" es sólo del kiosco.
- Permiso nuevo en el manifiesto: `android.permission.ACCESS_NETWORK_STATE` (lo pide el callback de red). Es normal, sin diálogo.

## Diseño

1. **Qué es una falla.** Función pura, sin Android, en un archivo nuevo `ui/KioskLoadFailure.kt` (objeto `KioskLoadFailure`): `fun matters(isMainFrame: Boolean, httpStatus: Int? = null): Boolean`. Es falla si es el documento principal y (no hay estado HTTP: error de conexión) o (estado ≥ 500). Los recursos secundarios (imágenes, scripts, iframes) nunca cuentan: el kiosco carga muchos y un `favicon` que falla no es "sin conexión". Constante `RETRY_MS = 10_000L` ahí o en la actividad.
2. **`KioskClient`** (el `WebViewClient` de la actividad):
   - `onPageStarted`: además de lo que hace hoy, marca `failed = false` y corta cualquier reintento programado.
   - `onReceivedError(view, request, error)` (la versión con `WebResourceRequest`, API 23 o más, está bien: `minSdk` es 26): si `KioskLoadFailure.matters(request.isForMainFrame)`, marca `failed = true` y lo deja en el log: `[KIOSCO] No se pudo abrir <origen> (<error.description>): se reintenta solo`. Origen con `KioskUrls.origin`, nunca la URL entera (puede traer token).
   - `onReceivedHttpError(view, request, response)`: lo mismo con `KioskLoadFailure.matters(request.isForMainFrame, response.statusCode)`; en el log el estado HTTP.
   - `onPageFinished`: si `failed`, muestra la pantalla de sin conexión y programa el reintento a los 10 s; si no, la esconde y cancela el reintento. (El orden en WebView es `onPageStarted` → `onReceivedError` → `onPageFinished`, también para la página de error.)
3. **La pantalla de sin conexión**: un `LinearLayout` vertical centrado, como `buildEmptyView()`, con fondo `R.color.bg` opaco (tapa el `WebView` entero), un `TextView` con `R.string.kiosk_offline` y un `Button` con `R.string.kiosk_retry`. Se agrega al `root` arriba del `WebView` y de la esquina de los toques **no**: la esquina tiene que quedar arriba de todo para que los cinco toques sigan andando con la pantalla puesta (agregar la pantalla antes que `buildCorner()`, o con `root.addView(..., índice)`). Oculta por defecto.
4. **Reintentos**: `private fun retry()`: si hay `webView` y `failed`, `webView.reload()` y línea `[KIOSCO] Se reintenta`. Un único `Runnable` programado con `handler.postDelayed`; `onPageStarted` y `onDestroy` lo sacan. El `NetworkCallback` se registra en `onStart` y se saca en `onStop` (`unregisterNetworkCallback`, en try/catch por si ya no estaba); llama a `retry()` por `handler.post` porque llega en otro hilo. `onResume` ya llama a `loadConfiguredUrl()` → `show()`: cambiar la condición de `show()` a `if (url != loadedUrl) {…} else if (failed) retry()`.
5. **El diálogo del PIN** (`askPin()`): sumar `.setNeutralButton(R.string.kiosk_reload) { _, _ -> if (pinAccepted(...)) reloadConfigured() }`, con `reloadConfigured()` = `loadedUrl = null; loadConfiguredUrl()` (así `show()` vuelve a hacer `loadUrl` con la URL guardada) y una línea `[KIOSCO] Se recarga a pedido`. Título del diálogo: pasa a `R.string.kiosk_pin_title` = "PIN del Panel Local" (sirve para las dos acciones); dejar `kiosk_exit_title` si otro lugar lo usa, si no sacarlo.
6. **Strings nuevos** en `app/src/main/res/values/strings.xml`, en el bloque del kiosco: `kiosk_offline` = "No se pudo abrir el kiosco. Sin conexión con el servidor. Se vuelve a intentar solo cuando haya red.", `kiosk_retry` = "Reintentar", `kiosk_reload` = "Recargar". Mismo tono que los demás (voseo, sin tecnicismos).
7. `onRenderProcessGone` ya rearma todo: dejar `failed = false` y esconder la pantalla ahí también.

## Tests

- `app/src/test/java/com/guardiavirtual/berrymed/ui/KioskLoadFailureTest.kt` (estilo de `KioskUserAgentTest.kt`, `kotlin.test`): error de conexión del documento principal cuenta; error de conexión de un recurso no cuenta; HTTP 503 del principal cuenta; HTTP 404 del principal no cuenta; HTTP 500 de un recurso no cuenta.
- `./gradlew test assembleDebug` en verde.

## Verificación en el emulador

Emulador `berry_tablet` (`emulator-5554`, adb en `~/Library/Android/sdk/platform-tools/adb`), ya encendido. Instalar el APK de debug (`adb install -r app/build/outputs/apk/debug/app-debug.apk`) y abrir el kiosco: `adb shell am start -n com.guardiavirtual.berrymed/.ui.KioskActivity`. Si el emulador no tiene URL del kiosco guardada, cargar una desde la configuración (vale cualquier https:// que exista, por ejemplo `https://kiosks-dev.ondoctor365.com/clinicaalem`, que es la de la foto del reporte).

1. Con red: el kiosco abre como siempre. Cinco toques, PIN `1515`, **Recargar**: vuelve a la URL configurada y en logcat sale `[KIOSCO] Se recarga a pedido`.
2. Sin red antes de abrir: `adb shell svc wifi disable` y `adb shell svc data disable`, matar la app (`adb shell am force-stop com.guardiavirtual.berrymed`) y volver a abrir el kiosco. Tiene que verse la pantalla de Berry Med (no la de Android) y en logcat `No se pudo abrir`. Cada 10 s, `Se reintenta`. Devolver la red (`svc wifi enable`, `svc data enable`): la página tiene que aparecer sola en pocos segundos, sin tocar nada. Verificar con `adb logcat -s BerryMed` y con capturas (`adb exec-out screencap -p > docs/capturas/plan-8-sin-red.png`; `docs/capturas/` no se commitea).
3. Que **Reintentar** sin red deje la pantalla puesta (no la de Android) y con red la saque.
4. Que los cinco toques sigan andando con la pantalla de sin conexión puesta.
5. Dejar el emulador con red.

Decir qué se probó y qué no. Lo que no se pudo probar en el emulador, decirlo, no darlo por hecho.

## Documentación

- `README.md`, sección **Kiosco**: un bullet nuevo después de "Atrás no sale": si la página no carga (la tablet todavía no se conectó al wifi, el servidor no contesta), el kiosco lo dice en su propia pantalla y vuelve a intentar solo cuando hay red y cada 10 s, con un botón **Reintentar**; y que en el diálogo del PIN está **Recargar**, que vuelve a abrir la URL del kiosco desde cero. Actualizar también la línea de la configuración paso a paso que dice cómo volver (`**NOTA**` de la tarea de app de inicio, y `kiosk_outside_exit` en strings si hace falta) sólo si queda incompleta; no reescribir más.
- `README.md`, tabla **Si algo falla**: fila "El kiosco dice que no se pudo abrir y no hay conexión." / "La tablet no está conectada al wifi." / "Conectá el wifi. El kiosco se recarga solo. Si no, cinco toques, PIN y **Recargar**."
- `docs/decisiones.md`: subsección nueva después de "Video del médico en negro…": **El kiosco se recarga solo (plan 8, 1.3.1)**, con el reporte de campo, la causa (`show()` cargaba sólo con URL distinta) y las decisiones de arriba en tres o cuatro bullets.
- KDoc de `KioskActivity` (el párrafo de arriba de la clase): una oración sobre el reintento y la recarga.

## Entrega

No commitear. Dejar los archivos en el árbol de trabajo y reportar: archivos tocados, resultado de `gradlew test assembleDebug`, qué se verificó en el emulador y qué no.
