# Plan 15: nada de un paciente queda para el siguiente, y el PIN

Plan para un agente (14 nov 2026). Sale de la revisión del estado del proyecto sobre la 1.3.7 (`main` en `4b58d23`): seis inconsistencias entre funciones. Ninguna viene de un reporte de campo; todas se verificaron leyendo el código. Los números de línea son de ese commit.

Regla que ordena los puntos 1 y 2: **una lectura pertenece a quien se la tomó**. Lo que se midió en el Panel Local no sale nunca a OnDoctor, y cada apertura del panel y cada consulta arrancan sin datos de la persona anterior.

## 1. La temperatura del termómetro no pasa de un paciente a otro

**Qué pasa hoy.** `ThermometerLink.latest` guarda la última lectura sin vencimiento. Sólo la borran `startSession` y `stopSession` (`service/TotemRuntime.kt:558` y `:603`). Quedan dos huecos:

- `startLocal`/`stopLocal` no la borran: una temperatura tomada en el Panel Local sigue en memoria al cerrarlo.
- Sin consulta, `sendOnce` arma el `POST` de prueba de cada 10 s con `sendableWindow(taken, thermo.latest())` (`:507`). Si la plataforma contesta `200`, `resumeSession` (`:581`) arranca la consulta **sin borrar nada**, y esa lectura vieja sigue saliendo en cada paquete (reemplaza a la temperatura de la plaqueta) hasta que alguien mida de nuevo.

Resultado: con el inicio perdido (wifi caído en el `start-monitoring`), al médico le llega como temperatura del paciente una lectura de otra persona, incluso una del Panel Local, que promete no enviar nada.

**Cambios.**

- `stopLocal()` y `startLocal()` llaman a `thermo.resetLatest()`. Lo del panel muere con el panel.
- La lectura lleva su instante. Hoy `lastReadingAtNano` va aparte del `latest` y no se borra con `resetLatest`: unificalo para que lectura e instante se guarden y se borren juntos, bajo el mismo lock (la pantalla de Testing y `localSnapshot` siguen pudiendo leer la edad; con la lectura borrada, no hay edad).
- Regla nueva, sin Android y con test (por ejemplo en `protocol/Measurements.kt` o junto a `SessionWatch`): una lectura es **reciente** si tiene a lo sumo `THERMO_FRESH_NANOS` = 60 s.
  - El `POST` de prueba (rama `!active` de `sendOnce`) sólo lleva la lectura del termómetro si es reciente. Una vieja no va en el cuerpo ni cuenta como dato útil para decidir si se prueba.
  - En `resumeSession`, dentro de `sessionLock`, si la lectura no es reciente se borra. Si es reciente se conserva: es del paciente que acaba de medirse mientras la tablet se perdía el inicio.
- `testingSnapshot` (`:1204`) arma la vista previa de "lo que saldría": tiene que usar la misma regla que `sendOnce` para el caso sin consulta.
- Con consulta activa no cambia nada: toda lectura que llegue durante la consulta se manda, como hoy.

**Tests.** La regla de reciente (borde en 60 s exactos, sin lectura, lectura futura por si el reloj salta). Si podés sacar de `sendOnce` la decisión "qué termómetro lleva este paquete" a una función pura, probá: sin consulta con lectura vieja → no va; sin consulta con lectura reciente → va; con consulta → va siempre.

## 2. El Panel Local arranca en blanco de verdad

**Qué pasa hoy.** `startLocal` (`:729`) sólo hace `live.clear()`. Quedan de la persona anterior:

- la **temperatura** (punto 1);
- la **presión**: `vitals.nibp` del parser y `nibpResultAtMillis` no se tocan, así que la tarjeta muestra el resultado anterior con su hora;
- el **filtro** y el **volumen** del estetoscopio: viven en `audio/StethoscopeLink.kt:74-77` y nadie los reinicia;
- la **velocidad**, la **ganancia** y las **derivaciones** del ECG: `lock()` de `ui/LocalPanelActivity.kt:450` no reinicia los `Segments`.

El README dice que cada desbloqueo arranca en blanco, con Corazón como filtro de arranque y el volumen en 6.

**Cambios.**

- **Presión.** No alcanza con `resetNibp()` en `startLocal`: la plaqueta repite el último resultado en cada trama de presión, y con `nibpResultSeen = null` el resultado viejo volvería a aparecer con la hora de ahora. Antes de tocar nada, mirá en `protocol/BmDataParser.kt`, `ProtocolTest` y `usb/BerryLink.kt` cuándo manda la plaqueta tramas de presión (sólo midiendo o también en reposo) y decilo en el PR. La regla que tiene que quedar, valga lo que valga esa respuesta: **el panel muestra una presión sólo si se pidió con Medir desde que se abrió**. Un indicador en el runtime (falso en `startLocal`, verdadero en `localNibpStart` cuando arranca) y `localSnapshot` entrega la presión vacía y sin hora mientras sea falso. La tarjeta queda en su estado vacío (`PressureCard.Empty`), no en "Última medición sin resultado". No cambies lo que se manda a OnDoctor en una consulta: si ves que ahí pasa lo mismo, anotalo en el PR y en `docs/decisiones.md` como duda a confirmar en la tablet, sin tocarlo.
- **Estetoscopio.** `startLocal` deja `stetho.filter = StethoFilter.DEFAULT` y `stetho.volume = StethoDsp.DEFAULT_VOLUME`. La pantalla tiene que reflejarlo al desbloquear (los segmentos del filtro y el número del volumen).
- **ECG.** Al bloquear o al desbloquear, los segmentos de velocidad, ganancia y derivaciones vuelven a su valor de arranque (los que hoy tiene al crear la pantalla; no inventes otros) y el papel se redibuja con ellos.
- La cámara de la tablet (frontal o trasera) puede quedar como estaba: no es un dato del paciente.

**Tests.** `PanelUiTest`: con el indicador en falso, la tarjeta de presión es `Empty` aunque el snapshot traiga un valor del parser. Lo que quede en funciones puras de valores de arranque, con test.

## 3. Testing no toca lo que usa el Panel Local

**Qué pasa hoy.** `testYkushSet` (`:1154`) sólo mira `sessionActive`, y `testNibpStop` (`:721`) también. Con el panel abierto (pantalla dividida, o Testing que quedó abierto atrás) se le puede apagar el puerto del otoscopio o del estetoscopio, o parar la presión que pidió quien atiende.

**Cambios.** Con `localMode`, las dos devuelven "El Panel Local está abierto: …" y no hacen nada, igual que `startTesting` (`:667`). **Leer estado** sigue andando: no cambia nada. Mirá también `testYkushSaveBoot`: escribe en la memoria del hub y no en los puertos, puede seguir.

**Tests.** Si la condición queda en algo probable sin Android, con test; si no, alcanza con el cambio y su línea en el README.

## 4. El PIN no se acepta antes de leer la configuración

**Qué pasa hoy.** `TotemRuntime.config` arranca en `BerryConfig()` y se reemplaza cuando `store.flow` emite (`:281-285`). `LocalPanelActivity.tryUnlock` (`:467`) y `KioskActivity.askPin` (`:440`, `:443`) comparan contra `runtime().config.localPanelPin`: en el primer instante después de arrancar el proceso vale `1515` aunque haya otro PIN guardado. `configLoaded` (`:132`) existe pero es privado.

**Cambios.**

- `configLoaded` se puede leer desde las pantallas.
- La decisión pasa a una función pura junto a `pinAccepted` (`config/`), con tres resultados: aceptado, incorrecto, todavía cargando. Con la configuración sin cargar nunca acepta.
- Panel Local y kiosco muestran "Cargando la configuración…" en ese caso (string nuevo) y dejan reintentar.
- Mirá también `ConfigStore.flow`: ante una excepción que no es `IOException` emite `BerryConfig()` y termina (hallazgo 18 de `docs/revisiones/revision-codigo-opus.md`). Eso volvería a dejar el `1515`. Que en ese caso no se pise la última configuración buena y quede una línea en la consola (`[CONFIG]`). Si el arreglo se complica, dejalo fuera y anotalo.

**Tests.** La función de tres resultados: PIN bueno sin cargar → cargando; PIN bueno cargado → aceptado; PIN vacío guardado (vale `1515`) cargado → aceptado con `1515`.

## 5. El Panel Local avisa de la consulta antes de pedir el PIN

**Qué pasa hoy.** Con una consulta enviando, el panel abre, pide el PIN y recién con el PIN correcto `startLocal()` contesta "Hay una consulta enviando: el Panel Local espera a que termine." (`LocalPanelActivity.kt:474-479`).

**Cambios.** Con el panel bloqueado y `runtime().sessionActive`, el mensaje se ve desde que aparece la pantalla del PIN, sin escribir nada, y se va solo cuando la consulta termina (el reloj de la cabecera ya corre cada segundo estando bloqueado: sirve para refrescarlo). El control de verdad sigue siendo `startLocal()` dentro de su lock; esto es sólo el aviso. No tapes con este mensaje un "PIN incorrecto" recién mostrado mientras no haya consulta.

## 6. El kiosco dice cuando el PIN está mal

**Qué pasa hoy.** En `KioskActivity.askPin` (`:429-450`) los botones **Entrar** y **Recargar** cierran el diálogo siempre; con el PIN mal no pasa nada y no se dice nada.

**Cambios.** Con el PIN mal (o la configuración todavía cargando, punto 4) el diálogo **queda abierto**, borra el campo y muestra el motivo ("PIN incorrecto", el mismo string `local_pin_wrong` del panel, o "Cargando la configuración…"). Para eso los botones no pueden usar el cierre automático de `AlertDialog`: se asignan en `setOnShowListener` sobre `getButton(...)`. **Cancelar** y tocar afuera cierran como hoy. Con el estilo del diálogo como está: no rediseñes.

## Lo que no entra

- El README de instalación (tareas 4, 5, 7 y 8) y los textos con "Chrome" o H390: van en otro plan.
- Los hallazgos viejos 8, 9, 10, 16, 17 y 21.
- La versión: **no la cambies**; la arma la sesión principal.

## Tests y entrega

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde. Hoy hay 307 tests y pasan todos.
- Si el emulador `berry_tablet` está prendido (`adb devices`), instalá el APK y verificá a mano: PIN incorrecto en el kiosco (queda abierto y lo dice) y en el panel; el panel abre con el volumen en 6, Corazón y el ECG en sus valores de arranque después de cambiarlos, cerrar y volver a entrar; Testing con el panel abierto no cambia puertos. Si no está prendido, no lo levantes: decí qué quedó sin verificar en pantalla. El emulador no tiene plaqueta, termómetro ni hub: lo de la temperatura y la presión se confirma en la tablet; decilo.
- Documentación, en el mismo PR:
  - `README.md`: "Cómo funciona" punto 6 (el termómetro: cuándo se borra y la regla de los 60 s con el inicio perdido), "Panel Local" (PIN, qué arranca en blanco, el aviso de la consulta), "Testing" (hub y presión con el panel abierto), "Kiosco" (PIN incorrecto).
  - `docs/decisiones.md`: una entrada con fecha 14 de noviembre de 2026 para el plan 15, y en "Pendiente" sacar de la lista de hallazgos los que quedan resueltos acá (15 y, si entró, 18) y sumar lo que haya que confirmar en la tablet.
- Rama `fix/datos-entre-pacientes-y-pin` desde `main`; PR contra `main`. Commits en español con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commiteá este plan.
- En el informe final: qué cambió por punto, qué decidiste donde el plan dejaba elegir, la respuesta a cuándo manda la plaqueta tramas de presión, y qué quedó sin verificar.
