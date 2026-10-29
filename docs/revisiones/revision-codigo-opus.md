# Revisión de código: Berry Med para Android

> Nota: escrito el 29 de octubre de 2026 sobre el commit `2883e0c` (`main` + PR #12). Los hallazgos 1 a 7 de la revisión de código se corrigieron en el PR #13; los números de línea son históricos.

Rama analizada: `feat/otoscopio-ampliado` (commit `2883e0c`, = `main` + PR #12). Se leyeron completos los 46 archivos de `app/src/main/java`, el manifiesto, los recursos relevantes (layouts del panel, filtro USB, temas), `build.gradle.kts` y los 19 archivos de test. `./gradlew test`: 134 tests, 0 fallas. No se tocó ningún archivo del repo salvo este informe.

## Resumen

**Estado general.** El código es bueno para lo que hace: los hilos tienen dueño claro, la lógica delicada (gate de sesión, reloj de Pusher, encendido del otoscopio, puertos del hub, DSP, parser) está separada de Android y probada, y los comentarios explican el porqué. El camino crítico (lectura USB → parser → POST cada segundo → órdenes de Pusher) está bien aislado del Panel Local y de Testing. Lo que encontré son sobre todo casos de borde de la política de sesión, que pueden cortar datos de una consulta real, y riesgos de hardware que el emulador no muestra.

**Los 5 problemas más graves:**

1. **El tope de 30 minutos corta unos 10 s de datos en cada consulta larga y no sirve como red de seguridad** (`TotemRuntime.kt:356-375`). Al vencer se detiene el envío como si fuera un corte propio. A los 10 s sale la prueba, la plataforma responde 200 y la consulta se reabre. Resultado: un hueco de unos 10 s cada 30 min (con el parser vaciado) y la tablet sigue enviando igual, así que el tope no frena nada.
2. **Una temperatura del termómetro sola no se manda nunca** (`TotemRuntime.kt:277-279`). La validez del paquete se mira antes de aplicar el termómetro. Si la plaqueta está caída o muda, la lectura del termómetro se descarta durante la consulta.
3. **El Panel Local puede apagar al salir un puerto del YKUSH3 que ya estaba encendido** (`PanelPorts.kt:18-19`). Si la lectura del hub falla una vez, el estado se toma como "apagado", el panel enciende el puerto, lo anota como suyo y lo apaga al cerrar. La teleconsulta siguiente queda sin otoscopio o sin auricular.
4. **El contador de 404 mezcla consultas y puede cortar el inicio** (`SessionWatch.kt:47-53`). Los 404 que llegan tarde de una consulta anterior suman en la nueva. Si además la plataforma tarda en abrir la toma después del `start-monitoring` (lo dejo como duda), la consulta se corta en los primeros segundos y pierde unos 10 s hasta la próxima prueba.
5. **`MetricsPoster` sólo acepta el código 200** (`MetricsPoster.kt:37-49`). Cualquier otro 2xx se reintenta 3 veces (el servidor recibe el paquete 3 veces) y cuenta como falla. Además, los tiempos de espera hacen que un paquete pueda tardar más de 90 s, y la cola tira los paquetes viejos sin dejar registro.

**PR #12 (otoscopio ampliado).** No toca el camino crítico (ni `TotemRuntime` ni el envío) y la lógica es razonable. El camino Camera2 (`PreviewView` en modo `compatible`) aguanta el cambio de padre. El riesgo está en el camino UVC: ahí se vuelve a poner la superficie de libuvc con `setSurfaceTexture` dentro de `onSurfaceTextureAvailable`, y eso depende del comportamiento interno de `TextureView` (hallazgo 14). **Recomiendo mergearlo**, después de probar en la tablet real por UVC: ampliar y reducir varias veces, congelar estando ampliado, bloquear con la imagen ampliada y apagar desde el ampliado. Los problemas 1 a 5 son de `main` y no del PR.

---

## Hallazgos (de más grave a menos grave)

### 1. El tope de sesión se reabre solo y deja un hueco en las consultas largas
- **Dónde:** `service/TotemRuntime.kt:356-375` (`stopSessionIfExpired` llama a `stopSession` con `byPlatform = false`), `service/SessionWatch.kt:40-43`, `TotemRuntime.kt:274-278` y `:299-301` (prueba y reanudación).
- **Categoría:** corrección (pérdida de mediciones).
- **Qué pasa:** al vencer los 30 min se detiene el envío y se programa la próxima prueba a 10 s (no a 90 s, porque `byPlatform` es `false`). Si la toma sigue abierta, la prueba recibe 200 y la consulta se reanuda. El parser se vació en `stopSession`, así que se pierden unos 10-11 s de ECG, SpO₂ y respiración, y la presión que se estaba mostrando.
- **Cómo se dispara:** una consulta de más de 30 minutos (o de más de lo configurado). También cuando el `stop-monitoring` se perdió y la plataforma mantiene la toma abierta: la tablet sigue enviando igual, que es justo lo que el punto 5 del README dice que el tope evita.
- **Qué cambiaría:** que el tope no corte si el último envío fue aceptado hace poco (el 404 ya detecta una toma cerrada). Otra opción: al vencer, pasar a modo prueba **sin** vaciar el parser y sin hueco, es decir seguir mandando y cortar recién con el primer 404. Si el tope tiene que existir igual, que al cortar llame a `watch.stopped(now, byPlatform = true)`, así no se reabre a los 10 s. Hay que actualizar el README.

### 2. El termómetro solo no se envía
- **Dónde:** `service/TotemRuntime.kt:277` (`isValidSample(window)`) antes de `:279` (`applyThermometer`); lo mismo en `testingSnapshot`, `:790-793`.
- **Categoría:** corrección.
- **Qué pasa:** `isValidSample` mira la temperatura de la plaqueta, no la del termómetro. Si no hay ondas ni signos (plaqueta desenchufada, o muda más de 5 s, que borra sus valores), el paquete se descarta, aunque haya una lectura del termómetro.
- **Cómo se dispara:** consulta en curso con la plaqueta caída o reconectando; el paciente se mide con el termómetro IR y el médico nunca ve la temperatura.
- **Qué cambiaría:** aplicar el termómetro antes de validar (`applyThermometer(window.vitalSigns, thermo.latest())` y después `isValidSample`). Agregar el test que falta: ventana vacía con lectura del termómetro → se envía.

### 3. El panel puede apagar al salir un puerto que ya estaba encendido
- **Dónde:** `service/PanelPorts.kt:18-19` (desconocido = apagado), `service/TotemRuntime.kt:559-563` (estetoscopio) y `:653-656` (otoscopio).
- **Categoría:** consistencia / hardware.
- **Qué pasa:** el KDoc dice que intentar encender "no rompe nada". Encender no, pero `turnedOn(port)` anota el puerto como del panel, y al cerrar el panel lo apaga. Si `readAll()` devolvió `null` (el hub no contestó, timeout HID o permiso todavía pendiente), un puerto que estaba encendido desde el arranque queda apagado.
- **Cómo se dispara:** tablet recién encendida (el permiso del YKUSH3 llega unos segundos después) o un timeout HID; alguien abre el panel y toca Escuchar o Encender; al salir se apagan el adaptador, el auricular o el otoscopio, y Chrome los pierde en la próxima teleconsulta hasta que alguien los prenda desde Testing.
- **Qué cambiaría:** si el estado previo es desconocido, después del `setPort(on)` volver a leer y anotar el puerto como propio sólo si antes se leyó **apagado** de verdad. Si la lectura previa falló, no anotarlo (es preferible dejar un puerto prendido de más). Agregar el caso al `PanelPortsTest`. De paso, rechazar en la configuración que dos equipos compartan puerto (hoy `ykushPortRoles` lo muestra como "otoscopio y estetoscopio"): apagar el otoscopio cortaría el estetoscopio.

### 4. Los 404 de una consulta anterior cuentan en la nueva; posible corte al inicio
- **Dónde:** `service/SessionWatch.kt:47-53`, `service/TotemRuntime.kt:303-304`.
- **Categoría:** corrección (duda sobre el comportamiento del servidor).
- **Qué pasa:** `watch.sent()` no sabe de qué consulta viene la respuesta. Los POST en vuelo de una consulta que terminó (hasta 2 hilos más 10 en cola) pueden volver con 404 después de `watch.started()` y dejar `notFound` en 1 o 2. `stillDue` evita que *esos* corten, pero el contador queda cargado, y el primer o segundo 404 de la consulta nueva la corta. Duda: si la plataforma manda el `start-monitoring` antes de tener la toma abierta, los primeros POST devuelven 404. Tres seguidos cortan la consulta a los 3 s, se vacía el parser y la consulta recién vuelve con la prueba, 10 s después.
- **Cómo se dispara:** un `stop-monitoring` seguido enseguida de un `start-monitoring` (el médico sale y vuelve a entrar), o un backend que abre la toma con algo de demora.
- **Qué cambiaría:** pasarle el `epoch` a `watch.sent(outcome, epoch)` e ignorar las respuestas de otro epoch. Además, no contar 404 en los primeros N segundos de la consulta, o pedir que los 3 404 abarquen al menos unos 5-10 s.

### 5. Envío: sólo 200 es éxito, reintentos sin espera, tiempos muy largos y descartes silenciosos
- **Dónde:** `net/MetricsPoster.kt:37-49`, `service/TotemRuntime.kt:62-70`.
- **Categoría:** corrección / robustez del envío.
- **Qué pasa:** (a) un 201, 202 o 204 se reintenta 3 veces (3 copias del mismo segundo de ECG) y se informa como falla. (b) Ante un 5xx se reintenta enseguida, sin los 500 ms que sí se esperan ante un error de red. (c) Los tiempos de 10 + 20 + 20 s por intento, con 3 intentos, dan más de 90 s por paquete: con 2 hilos, la cola de 10 se llena en 10 s y `DiscardOldestPolicy` tira paquetes sin dejar registro. Además, un paquete que llega un minuto tarde ya no le sirve al médico, que mira en vivo. (d) Al tener 2 hilos, los paquetes pueden llegar desordenados.
- **Cómo se dispara:** una API que responde `201 Created`, o una red lenta (wifi con poca señal) en medio de una consulta.
- **Qué cambiaría:** tomar `response.isSuccessful` como éxito; agregar `callTimeout(~4 s)` y reintentar como mucho una vez; dormir también tras un 5xx; usar un `RejectedExecutionHandler` que registre cuántos paquetes se descartaron y lo muestre en `lastPost`. Confirmar con el backend qué código devuelve y si le importa el orden (el `timestamp` va en el JSON).

### 6. Al desenchufar la plaqueta, `nibpRunning` queda en `true`
- **Dónde:** `usb/BerryLink.kt:174-237` (`readLoop`), `:288-300` (`release`), `:261-273` (`checkNibpTimeout`, que sólo corre dentro de `readLoop`).
- **Categoría:** corrección.
- **Qué pasa:** si la plaqueta se desconecta mientras mide la presión, ni `release` ni `probe` bajan la bandera. El panel muestra "Midiendo · manguito N mmHg" congelado, Medir queda deshabilitado y un `start-blood-pressure` del médico se rechaza ("ya está midiendo") hasta que vuelva la plaqueta y pasen 30 s sin estado o 3 min desde el inicio.
- **Cómo se dispara:** desenchufar y volver a enchufar el cable (la prueba que pide el README en "Instalar", punto 6) con una presión en curso.
- **Qué cambiaría:** en `release()` y al conectar en `probe()`, poner `nibpRunning = false` (la plaqueta reinicia y la medición se perdió de todas formas).

### 7. Carrera entre `startNibp` y `stopNibp`
- **Dónde:** `usb/BerryLink.kt:74-77` y `:80-101`.
- **Categoría:** corrección (hilos).
- **Qué pasa:** `stopNibp` baja la bandera **fuera** de `writeLock` y después espera el lock para escribir STOP. Si en ese momento `startNibp` tiene el lock, escribe el inicio, sube la bandera y suelta; después sale el STOP. El equipo queda parado y `nibpRunning` en `true`, con `lastNibpFrameAt = 0`, así que sólo lo libera el tope de 3 min.
- **Cómo se dispara:** un estado terminal en el hilo USB (o el STOP de `startSession`) que coincide con un Medir o con un `start-blood-pressure`. Es poco probable, pero deja la presión bloqueada 3 minutos.
- **Qué cambiaría:** que `stopNibp` baje la bandera y escriba dentro del mismo `synchronized(writeLock)`.

### 8. Audio USB: no se comprueba por dónde se enrutó
- **Dónde:** `audio/StethoscopeLink.kt:188` (`record.preferredDevice`), `:223` (`track.preferredDevice`), `:237-238`.
- **Categoría:** hardware.
- **Qué pasa:** `setPreferredDevice` es una preferencia. Con dos placas de audio USB a la vez (adaptador 3302:29E3 y H390), varias tablets o versiones de Android sólo exponen la última placa conectada, o enrutan la entrada a la del auricular. Puede quedar grabando el micrófono del H390 o sonando por el parlante, sin que la app se entere.
- **Cómo se dispara:** encender los puertos en otro orden, o desenchufar y volver a enchufar una de las placas con el panel abierto.
- **Qué cambiaría:** después de `startRecording()` y `play()`, comparar `record.routedDevice?.id` con `input.id` y `track.routedDevice?.id` con `output.id`. Si no coinciden, cortar y avisar (sobre todo si la salida termina en el parlante: se acopla). Agregar `addOnRoutingChangedListener` a los dos para cortar si Android los mueve en caliente. Mostrar en Testing el dispositivo efectivamente enrutado.

### 9. Otoscopio desenchufado mientras transmite: no se detecta y libuvc puede colgar el hilo
- **Dónde:** `otoscope/UvcOtoscope.kt:150-167` (`release`), `service/TotemRuntime.kt:102-104` (un solo hilo para la cámara y el hub).
- **Categoría:** hardware / consistencia.
- **Qué pasa:** nadie escucha `USB_DEVICE_DETACHED` del otoscopio. Si se desenchufa el hub o el cable con la imagen abierta: por Camera2 la vista queda negra y el estado sigue en ON; por UVC, libuvc (fork de UVCCamera) es conocido por colgarse o romperse en código nativo cuando el dispositivo desaparece en pleno streaming. Si `stopPreview` o `destroy` se cuelgan en `berry-panel-oto`, ese hilo es también el que apaga y prende el puerto: Encender, Apagar y el cierre del panel quedan encolados para siempre. Una caída nativa se lleva el proceso entero, servicio incluido (vuelve por `START_STICKY`, pero en ese rato no se reciben órdenes).
- **Cómo se dispara:** tirar del cable del hub o del otoscopio con la imagen abierta; un corte del dock.
- **Qué cambiaría:** registrar un receptor de `USB_DEVICE_DETACHED` mientras el panel está abierto y, si se va el otoscopio, cerrar la fuente y pasar a OFF. Separar el hilo de la cámara UVC del hilo del hub, o poner un tope al cierre (Future con timeout): si no vuelve, seguir con el puerto igual. Probar en la tablet el desenchufe por UVC.

### 10. El camino Camera2 toma cualquier cámara externa
- **Dónde:** `otoscope/OtoscopeProbe.kt:16-23`, `otoscope/CameraXOtoscope.kt:34`.
- **Categoría:** hardware.
- **Qué pasa:** `cameraSeen()` da `true` con cualquier cámara `LENS_FACING_EXTERNAL`, y CameraX abre la primera. Si el tótem tiene o llega a tener otra cámara USB (por ejemplo una webcam para la videollamada), el panel elige Camera2 aunque el otoscopio no esté y muestra la otra cámara como si fuera el otoscopio.
- **Cómo se dispara:** cualquier segunda cámara UVC enchufada al dock.
- **Qué cambiaría:** contar las cámaras externas antes de encender y elegir la que aparece *después* de encender el puerto (comparar la lista de ids antes y después). Si hay más de una, ir por UVC filtrando por 349C:3301.

### 11. Ancho de banda isócrono: otoscopio y dos placas de audio en el mismo hub
- **Dónde:** `otoscope/UvcOtoscope.kt:121` (`UVCCamera.DEFAULT_BANDWIDTH`), `otoscope/OtoscopeResolution.kt`.
- **Categoría:** hardware.
- **Qué pasa:** el panel deja usar el otoscopio y el estetoscopio a la vez. Los tres equipos son isócronos y cuelgan del YKUSH3 (USB 2.0). Una cámara MJPEG suele pedir la interfaz alternativa de mayor ancho de banda aunque la imagen sea chica; con el factor 1.0 de libuvc puede no quedar lugar para el audio (cortes, o que `startRecording` o `startPreview` fallen con ENOSPC). El comentario de `OtoscopeResolution` cubre la plaqueta (bulk), no el audio.
- **Cómo se dispara:** Escuchar con el otoscopio encendido, o encender el otoscopio mientras se escucha.
- **Qué cambiaría:** probarlo en el tótem real. Si falla, pasar un factor de ancho de banda menor (0,5 o 0,6) en `setPreviewSize` y mostrar en la tarjeta el error real de libuvc.

### 12. El permiso USB del otoscopio se rechaza solo si todavía no está el de la cámara
- **Dónde:** `usb/UsbHub.kt:39-50` y `:144-148`, README "Instalar y dejarla sola", punto 2.
- **Categoría:** hardware / consistencia (duda razonable, a confirmar en la tablet).
- **Qué pasa:** desde Android 9 o 10, para un dispositivo USB de clase video `UsbManager.requestPermission` responde "denegado" al instante, sin mostrar diálogo, si la app no tiene el permiso `CAMERA`, y el filtro de "Usar de forma predeterminada" tampoco ofrece la app. El servicio pide el permiso del otoscopio apenas lo ve, antes de que nadie haya abierto el panel. Queda anotado como "ya pedido" y no se vuelve a pedir hasta tocar el botón. El panel lo arregla en parte (pide `CAMERA` antes de Encender y, si se agota la espera, llama a `forgetAsked()`), pero el README promete que se acepta en la instalación.
- **Cómo se dispara:** instalación nueva, con el hub prendiendo los tres puertos al arrancar.
- **Qué cambiaría:** no pedir el permiso del otoscopio mientras falte `CAMERA` (que `totemDevices` lo saltee). Pedir `CAMERA` desde `MainActivity` en la instalación. Aclararlo en el README.

### 13. La imagen del otoscopio puede abrirse después de Apagar
- **Dónde:** `ui/LocalPanelActivity.kt:439-464` (`onOtoscopeFound`).
- **Categoría:** corrección (carrera entre la pantalla y el hilo del otoscopio).
- **Qué pasa:** el hilo del otoscopio pasa a ON y publica `done(path, …)` en el hilo de la pantalla. Si antes de que corra el usuario toca Apagar (o el panel se bloquea y se desbloquea dentro de los 8 s), `localOtoscopeOff()` ya apagó el puerto. Igual `onOtoscopeFound` sólo mira `unlocked` y abre la cámara sobre un puerto apagado: queda `otoscope != null` con el estado en OFF, el botón dice "Apagar" y la imagen está negra.
- **Cómo se dispara:** doble toque en Encender y Apagar justo cuando aparece el otoscopio, o bloquear y desbloquear rápido.
- **Qué cambiaría:** que `onOtoscopeFound` siga sólo si `runtime.otoscopeState == ON` y el pedido es el vigente. Lo más limpio es un número de pedido en `OtoscopePower` que `switchOff` invalida, y que `done` lleve el número.

### 14. PR #12: el ampliado por UVC depende de detalles internos de `TextureView`
- **Dónde:** `otoscope/UvcOtoscope.kt:64-79`, `ui/LocalPanelActivity.kt:335-363`.
- **Categoría:** hardware (no verificable en el emulador) / calidad.
- **Qué pasa:** (a) `setSurfaceTexture(previous)` se llama *dentro* de `onSurfaceTextureAvailable`. Después de ese callback, `TextureView.getTextureLayer` le hace `setDefaultBufferSize(ancho, alto de la vista)` a la superficie guardada, que es la de libuvc. Como libuvc fija la geometría con `ANativeWindow_setBuffersGeometry` debería seguir andando, pero conviene verlo: el riesgo es una imagen negra o estirada después de ampliar o reducir. Si `previous.isReleased`, no se hace nada y libuvc sigue dibujando en una superficie liberada. (b) El desenfoque con `RenderEffect` se aplica a un panel que se redibuja a 60 cuadros por segundo (ECG y ondas). En una tablet económica, con el MJPEG decodificándose al mismo tiempo, puede trabar la imagen del otoscopio.
- **Cómo se dispara:** ampliar y reducir varias veces por UVC; bloquear con la imagen ampliada.
- **Qué cambiaría:** probarlo en la tablet antes de mergear. Si libuvc pierde la imagen, dejar fija la `TextureView` y mover un contenedor con `scaleX`/`scaleY` y `translation` en vez de cambiarla de padre (así no se destruye la superficie). Mientras está ampliado, frenar los `tick()` del panel (no se ven) o desenfocar una captura fija.

### 15. Testing puede tocar el hub y la presión con el Panel Local abierto
- **Dónde:** `service/TotemRuntime.kt:765-770` (`testYkushSet` sólo mira `sessionActive`), `:473-478` (`testNibpStop` no mira `localMode` ni `testing`).
- **Categoría:** consistencia.
- **Qué pasa:** en pantalla dividida, desde Testing se puede apagar el puerto del otoscopio o del estetoscopio que está usando el panel, o parar la presión que pidió quien atiende. El README dice que con el panel abierto Testing no inicia la prueba, pero estos botones no dependen de la prueba.
- **Cómo se dispara:** Testing y el Panel Local abiertos a la vez en pantalla dividida.
- **Qué cambiaría:** con `localMode`, devolver "El Panel Local está abierto" en `testYkushSet` y `testNibpStop`. Pedir también `testing` para `testNibpStop`, igual que `testNibpStart`.

### 16. Una trama con el largo roto se lleva hasta 257 bytes buenos
- **Dónde:** `protocol/BmDataParser.kt:53-63`.
- **Categoría:** hardware / protocolo.
- **Qué pasa:** si el checksum falla, se descarta todo lo que declara el byte de largo (`dropUntil(end)`). Si lo que se corrompió es justamente ese byte (o el `55 AA` era falso), se tiran hasta unos 20 cuadros de ECG válidos que venían atrás, y se espera a juntar 257 bytes antes de decidir.
- **Cómo se dispara:** un byte perdido en el USB CDC (pasa con docks y cables largos).
- **Qué cambiaría:** con checksum malo, descartar sólo `start + 2` y volver a sincronizar en la siguiente cabecera. Poner un tope razonable al largo según el tipo de trama (ECG = 12 bytes, ondas = 6, etc.) y resincronizar si lo supera. Agregar un test con un largo corrupto seguido de tramas buenas.

### 17. La prueba de la plaqueta le escribe comandos binarios a cualquier puerto
- **Dónde:** `usb/BerryLink.kt:139-147`.
- **Categoría:** hardware.
- **Qué pasa:** todo puerto candidato que no mandó nada en 400 ms recibe la secuencia de arranque `55 AA …` antes de saber si es la plaqueta. El termómetro (que sólo habla cuando alguien mide) la recibe en cada arranque. Con el firmware actual es probablemente inofensivo, pero cualquier equipo serie que se agregue al dock va a recibir bytes que no espera.
- **Cómo se dispara:** cada arranque con el termómetro enchufado y la plaqueta todavía sin identificar.
- **Qué cambiaría:** escribir el arranque sólo en puertos de ST (la plaqueta es un STM32, según el README). A un puerto que no es de ST darle más tiempo de escucha y, si no habla, dejarlo para el termómetro sin escribirle.

### 18. Un error de lectura de la configuración deja al tótem sordo hasta reiniciar
- **Dónde:** `config/ConfigStore.kt:29-36`; relacionado, `ui/LocalPanelActivity.kt:272` con `service/TotemRuntime.kt:131`.
- **Categoría:** corrección (robustez).
- **Qué pasa:** ante una excepción que no es `IOException`, `.catch { emit(BerryConfig()) }` emite la configuración vacía y **termina el flujo**. `pusher.apply()` desconecta Pusher (falta el canal), `metricsUrl()` da null y no se vuelven a ver guardados posteriores. Relacionado: hasta que DataStore carga, `config` es `BerryConfig()`, así que en el primer segundo después del arranque el panel acepta 1515 aunque se haya configurado otro PIN.
- **Cómo se dispara:** poco probable (DataStore ya trae un manejador de corrupción), pero si pasa, la falla es silenciosa y total.
- **Qué cambiaría:** en el `catch`, registrar el error en la consola y no emitir la configuración vacía (que `config` siga con la última buena). En el panel, no aceptar el PIN hasta que se haya cargado la configuración (un `configLoaded` volátil).

### 19. Android 15: el sistema obliga a dibujar de borde a borde y Main y Testing no manejan los márgenes
- **Dónde:** `res/values/themes.xml:6-7`, `res/layout/activity_main.xml` y `activity_testing.xml` (raíz `ScrollView` sin márgenes de sistema).
- **Categoría:** Android 14/15.
- **Qué pasa:** con `targetSdk 35`, en Android 15 la ventana se dibuja de borde a borde y se ignoran `statusBarColor` y `navigationBarColor`. Los 28 dp de arriba más o menos salvan la barra de estado, pero con navegación de 3 botones (48 dp) el final de la pantalla (Guardar, Pedir permiso USB, la consola de Testing) queda debajo de la barra.
- **Cómo se dispara:** cualquier tablet con Android 15 y navegación por botones.
- **Qué cambiaría:** `enableEdgeToEdge()` más `ViewCompat.setOnApplyWindowInsetsListener` en la raíz, que sume `systemBars()` al padding. Es poco código. El Panel Local ya oculta las barras.

### 20. Guardar la configuración borra usuario, contraseña y endpoint
- **Dónde:** `ui/MainActivity.kt:244-268` (`readForm`), `config/BerryConfig.kt:10-12`, `config/ConfigStore.kt:45-47`.
- **Categoría:** calidad.
- **Qué pasa:** el formulario no tiene `apiEndpoint`, `apiUsername` ni `apiPassword`, y `readForm` arma el `BerryConfig` sin ellos, así que cada guardado los pisa con `"metrics"`, `""` y `""`. Hoy no importa (OnDoctor no autentica), pero es configuración muerta que parece viva: `MetricsPoster` sigue armando un header `Authorization`.
- **Qué cambiaría:** sacarlos de `BerryConfig`, `ConfigStore` y `MetricsPoster`, o copiarlos de la configuración cargada en `readForm`.

### 21. El README exige `https://`, pero el código acepta `http://` y permite tráfico sin cifrar
- **Dónde:** `config/BerryConfig.kt:101-104` (`hasWebScheme`), `AndroidManifest.xml:30` (`usesCleartextTraffic="true"`), README línea 70.
- **Categoría:** calidad / seguridad.
- **Qué pasa:** se pueden guardar y mandar signos vitales por HTTP plano. El mensaje de `missingFields` dice "que empiece con https://" pero acepta `http`.
- **Qué cambiaría:** aceptar sólo `https://` y sacar `usesCleartextTraffic`. Si hace falta HTTP para un entorno local, que sea con un `network_security_config` sólo para el build debug.

### 22. `OtoscopePower.state` se lee sin sincronización
- **Dónde:** `otoscope/OtoscopePower.kt:44-49`, `service/TotemRuntime.kt:117` y `:746-747`.
- **Categoría:** calidad (hilos).
- **Qué pasa:** se escribe dentro de métodos `@Synchronized` y se lee desde el hilo de la pantalla sin lock ni `@Volatile`. Según el modelo de memoria de Java, la pantalla puede ver un estado viejo (en ART casi nunca pasa, pero el hallazgo 13 ya depende de ese estado).
- **Qué cambiaría:** marcar `state` y `path` como `@Volatile`, o leerlos con un getter sincronizado.

### 23. `MonitorService`: un `startForeground` por cada cambio de texto y las fallas sólo van a logcat
- **Dónde:** `service/MonitorService.kt:75-103`.
- **Categoría:** calidad / Android 14.
- **Qué pasa:** cada cambio de notificación vuelve a llamar a `startForeground` desde un hilo de fondo. Si falla (por ejemplo, Android 14 rechaza el tipo de servicio), la excepción se registra sólo en logcat, no en la consola de Testing. Si eso pasa en `onCreate`, unos segundos después Android cierra la app con "did not call startForeground in time" y el motivo real no se ve en la tablet.
- **Qué cambiaría:** `startForeground` sólo en `onCreate` y `onStartCommand`; para cambiar el texto, `NotificationManagerCompat.notify(NOTIFICATION_ID, …)`. Mandar el error también a `runtime.log`.

### 24. `forgetAsked()` desde el otoscopio vuelve a pedir el permiso de todos los equipos
- **Dónde:** `service/TotemRuntime.kt:681`, `usb/UsbHub.kt:52-55`.
- **Categoría:** consistencia.
- **Qué pasa:** para volver a pedir el permiso del otoscopio se olvidan *todos* los pedidos. Un termómetro o un hub que alguien rechazó a propósito vuelve a sacar su diálogo arriba del panel.
- **Qué cambiaría:** `forgetAsked(deviceId)` sólo para el otoscopio.

### 25. Excepciones tragadas sin registro en el panel y en el audio
- **Dónde:** `ui/LocalPanelActivity.kt:400-405` y `:418-420` (`bindCamera`), `audio/StethoscopeLink.kt:70-74` (`devices`), `usb/YkushHub.kt:128-134`.
- **Categoría:** calidad.
- **Qué pasa:** la tarjeta dice "no se pudo abrir la cámara", pero el motivo no queda en ningún lado. En el audio, una excepción de `getDevices` se ve como "No aparece el adaptador".
- **Qué cambiaría:** mandar el error a `runtime.log(...)` en cada uno de esos `catch`, para que aparezca en la consola de Testing, que es lo que se mira en el tótem.

### 26. `TotemRuntime` hace demasiadas cosas y hay duplicación
- **Dónde:** `service/TotemRuntime.kt` (857 líneas): sesión y envío, Testing, estetoscopio, otoscopio, YKUSH3 y las vistas de pantalla. `sleep()` con manejo de interrupción repetido en 4 clases; `"start-monitoring"` y `"stop-monitoring"` escritos en `BerryConfig`, `ConfigStore`, `MainActivity` y `SessionPusher`; la edad del termómetro calculada igual en `localSnapshot` y `testingSnapshot`; las tablas de ganancia en `BmDataParser`, `Pm6750Protocol` y `MainActivity`. El KDoc de `localStethoStart` (`:523-528`) quedó con un salto de línea roto, y el de `DeviceInventory` habla de "antes de escribir el Panel Local".
- **Categoría:** calidad.
- **Qué cambiaría:** sacar un `LocalPanelController` (estetoscopio, otoscopio, puertos) y un `TestingController`, y dejar en `TotemRuntime` sólo la sesión y el envío, que es lo crítico y lo que más conviene poder probar sin Android (ver "Tests que faltan"). Centralizar las constantes de eventos y ganancias.

---

## Lo que está bien

- **`SessionGate` y la decisión dentro de `sessionLock`**: consulta y panel nunca juntos, con el `epoch` para descartar respuestas viejas y una prueba que recorre todas las combinaciones. No tocar.
- **El `LiveFeed` como segundo lector del parser**: el panel no le roba muestras al POST. Buffer circular con cursor, simple y probado.
- **El ciclo de envío a ritmo fijo, sin esperar la respuesta, con tramos de un segundo** (`splitWindow`) por el límite de Pusher: buena decisión y bien explicada.
- **`SessionPusher`**: reutiliza el cliente cuando la librería se rinde y lo rearma sólo si quedó trabado en CONNECTING, con espera exponencial; ignora los eventos de clientes reemplazados. Es el conocimiento de la librería que cuesta conseguir.
- **Un hilo de trabajo por equipo del panel** (estetoscopio, otoscopio) con `epoch`, para que los pedidos y los cierres salgan en orden y el puerto se apague siempre después de soltar la cámara.
- **`StethoscopeLink.stop()`**: no se queda colgado si la lectura del adaptador no vuelve, y cada sesión de audio es independiente.
- **El permiso USB por dispositivo con espera de gracia** (`PermissionRequests`), para no tapar Chrome con diálogos.
- **Servicio `connectedDevice` y `UsbAttachActivity` sin pantalla** para el permiso predeterminado: correcto para Android 14 y 15.
- **El DSP del estetoscopio** (biquads en double, recorte suave, decimación por pico) y la elección de audio por nombre que nunca toma el micrófono del auricular.
- **Las propiedades de compatibilidad de cámara en el manifiesto** (Android 14), bien documentadas.

## Tests que faltan

1. **Política de envío de `TotemRuntime` (el más valioso).** Extraer de `sendOnce` y `deliver` una clase sin Android (`SendPolicy`: ventana, lectura del termómetro, estado del gate, respuesta → qué mandar y qué hacer) y probar: (a) ventana vacía más termómetro → se envía (hallazgo 2); (b) consulta que vence a los 30 min con la plataforma respondiendo 200 → no hay hueco ni se vacía el parser (hallazgo 1); (c) 404 de un epoch viejo no cuentan para la consulta nueva (hallazgo 4); (d) un 201 cuenta como éxito y no se reintenta (hallazgo 5).
2. **`PanelPorts` con estado desconocido.** `toTurnOn([1], [null,null,null])` seguido de un encendido exitoso no debe dejar el puerto 1 como "del panel" si después se lee que ya estaba encendido (hallazgo 3).
3. **`BerryLink` y la presión ante una desconexión.** Con un `SerialHandle` falso: iniciar la presión, simular `UsbLost` y comprobar que `nibpRunning` vuelve a `false` y que `startNibp` funciona apenas reconecta (hallazgo 6). La misma clase falsa sirve para la carrera entre `stopNibp` y `startNibp` (hallazgo 7).
4. **`BmDataParser` resincroniza tras un largo corrupto.** Una trama con el byte de largo cambiado (por ejemplo 0xF0) seguida de 10 tramas de ECG válidas: tienen que llegar las 10 (hallazgo 16).
5. **`MetricsPoster` contra `MockWebServer`** (`okhttp3:mockwebserver`, sólo para tests): 200, 201, 404 (sin reintento), 503 (3 intentos con espera) y el tiempo máximo por paquete (hallazgo 5).
6. **`OtoscopePower` con número de pedido.** Pasar a ON, después `switchOff()`, y que un `Open` del pedido anterior quede rechazado (hallazgo 13).
