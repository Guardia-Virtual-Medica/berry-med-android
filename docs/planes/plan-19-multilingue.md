# Plan 19: Berry Med en varios idiomas

Análisis, spike y plan para un agente (27 nov 2026). **La migración parte de `origin/main`** (`41db2df`, versión 1.4.0, `versionCode` 18). El PR 46 (plan 18, `fix/teleconsulta-simple`) está en espera y este plan no depende de él.

El análisis y el spike se hicieron sobre `fix/teleconsulta-simple` en `d39a4b5`, que era la rama abierta. Para los idiomas la diferencia con `main` es chica (299 entradas en `strings.xml` en vez de 301, y unas pocas frases menos en el código): las cifras de la parte A son de esa rama y las listas reales se generan contra `main`. Tiene cuatro partes:

- **A. Análisis**: qué textos tiene la app y dónde están.
- **B. Spike**: cómo se hace multilingüe, probado con un prototipo en el emulador.
- **C. Migración**: el diseño y los pasos, un PR por paso.
- **D. Pruebas**: las automáticas (ya escritas en el spike) y las manuales.

Antes de ejecutar hay **decisiones de Nico** (al final de la parte B). El plan está escrito con la opción recomendada en cada una.

Material del spike:

- `docs/planes/plan-19-spike-multilingue.patch`: el prototipo entero como parche (26 archivos). Está hecho sobre `d39a4b5` (la rama del plan 18): sobre `main` es material de consulta, no se aplica.
- `.claude/worktrees/spike-i18n` (rama `spike/i18n-multilingue`, sin commitear): el mismo prototipo, compilado.
- `docs/capturas/plan-19/`: capturas del emulador (la carpeta está en `.gitignore`: quedan en esta Mac).

---

## A. Análisis: los textos de la app

La app no está tan lejos: usa vistas XML con `strings.xml`, y más de la mitad de los textos ya pasa por ahí. Lo difícil no son los layouts sino lo que arma texto **fuera de una pantalla**: el runtime, los enlaces con los equipos y los modelos sin Android.

### A.1 En números

| Dónde | Cuántos | Estado |
|---|---|---|
| `res/values/strings.xml` | 301 entradas, 1.896 palabras; 13 con parámetros, 0 plurales | Ya son diccionario. Están en español rioplatense (voseo: «Elegí», «Tocá», «Prendé»). |
| Layouts (`android:text`, `hint`, `contentDescription`) | 27 escritos a mano en 3 archivos | 14 se traducen, 12 son fijos (`II`, `x1`, `mmHg`…) y 1 es un número con coma (`12,5`). |
| Layouts, `tools:text` | 51 | Son de muestra para el editor: no cuentan. |
| Kotlin, frases fuera del log | 326 literales en 33 archivos | Unos 275 se ven en pantalla; unos 40 son motivos que sólo van al log; unos 10 no son texto (cabeceras HTTP, JSON). |
| Kotlin, líneas de log (`[USB] …`, 21 etiquetas) | 175 | Van a logcat y a la consola de Testing (y al archivo `berry.log`, que llega con el plan 18). |

Diccionario final estimado: **unas 600 entradas, 3.500 a 4.000 palabras por idioma**.

Para comparar: el lint de Android sólo ve 42 textos en layouts y 10 `setText` con literales. Los ≈275 del código sin Android no los ve nadie; por eso el spike trae un test propio (parte D).

### A.2 Por archivo (Kotlin, sin contar el log)

| Archivo | Frases | Qué son | Paso |
|---|---|---|---|
| `service/TotemRuntime.kt` | 88 | Estado de la pantalla principal, respuestas a los botones del Panel Local y de Testing, la notificación; ~12 son motivos de log | 4 |
| `ui/TestingActivity.kt` | 47 | Las tarjetas de Testing, armadas concatenando pedazos | 2 |
| `ui/status/StatusPanelUi.kt` | 23 | El panel lateral (hecho en el spike) | 3 |
| `ui/panel/PanelUi.kt` | 22 | Estados del Panel Local («En vivo», «Señal débil») | 3 |
| `usb/DeviceInventory.kt` | 19 | Testing → Dispositivos que ve Android | 4 |
| `audio/CallAudioRouter.kt` | 17 | Testing → descripción del audio de la llamada | 4 |
| `tele/TeleStreamer.kt` | 16 | Casi todo motivos de log; 3 mensajes de estado | 4 |
| `service/TestingSession.kt` | 15 | Veredictos de las pruebas («No llega la onda de ECG») | 3 |
| `tele/LiveKitBackend.kt` | 12 | Por qué no se publicó un instrumento | 4 |
| `audio/StethoscopeLink.kt` | 9 | Mensajes del estetoscopio al Panel Local | 4 |
| `config/BerryConfig.kt` | 8 | «Falta configurar: …» | 4 |
| `otoscope/*` (7 archivos) | 16 | Por qué no abrió el otoscopio; «por UVC», «por Camera2» | 4 |
| `ui/MainActivity.kt`, `usb/KnownDevices.kt`, `service/MonitorService.kt`, `service/TotemState.kt` | 4 c/u | Chips de estado, roles USB, notificación, valores iniciales | 2 y 4 |
| `ui/panel/PanelFormat.kt` | 3 | «recién», «hace N min», «hace N h» | 3 |
| Resto (10 archivos) | 13 | Sueltos; varios no son texto | 4 |

La lista completa, línea por línea, es `app/src/test/resources/i18n/textos-en-codigo.txt` del parche (se regenera sola).

### A.3 Lo que no es sólo «pasar el texto a un recurso»

1. **Lógica atada al texto.** Hay que cortarla antes de traducir o se rompe en otro idioma:
   - `LocalPanelActivity.kt:722`: decide si mostrar un aviso con `text.startsWith("No se pudo") || text.startsWith("El panel")`.
   - `PanelUi.kt:360`: `label.removePrefix("por ")` sobre «por UVC».
   - `StatusPanelUi`: `state.second == NO_PERMISSION` y `PanelUi.heartNote(local) == PanelUi.LEADS_OFF` (comparan frases).
   - `MainActivity.kt:569`: `value == "—"`.
2. **Frases armadas con pedazos.** `"Puerto $port ${if (on) "encendido" else "apagado"}"`, `"falta el permiso de $what"` con «la cámara» / «el micrófono», las tarjetas de Testing («electrodos: » + «sueltos»). En otro idioma el orden, el género y el artículo cambian: cada variante pasa a ser una entrada entera.
3. **Plurales a mano.** `"paquete descartado" / "paquetes descartados"`, «hace N min», «N s».
4. **Nombres de arritmia.** `PanelFormat.arrhythmia` muestra al médico el código del protocolo cambiando `_` por espacio: «fibrilacion ventricular», «extrasistoles ventriculares multiples» (hoy sin tildes). Son 12 códigos que también viajan a la API: el código no se toca; lo que se muestra pasa a 12 entradas del diccionario. **Es texto clínico: lo revisa un médico en cada idioma.**
5. **Fechas y números con el idioma fijo.** `PanelFormat` usa `Locale("es", "AR")` y los patrones `"EEEE d 'de' MMMM 'de' yyyy"` y `"dd/MM/yyyy HH:mm"`; `value()` cambia el punto por coma a mano; Testing usa `Locale("es")`. En cambio `StethoscopeLink.kt:319` y `:323` formatean con el idioma **por defecto**, que desde ahora es el de la app: el log cambiaría de «1,5» a «1.5» según el idioma.
6. **Datos que parecen texto y no se traducen.** Lo que va a la API o se guarda: `operacion` / `monitor` / `diagnostico`, `adulto` / `nino` / `neonato`, los códigos de arritmia, los nombres de evento de Pusher, `parlante`. El cuerpo del `POST` ya usa `Locale.US` explícito: no cambia.
7. **El servicio no es una pantalla.** La notificación y el nombre de su canal salen del servicio; el runtime arma textos con el contexto de la aplicación.
8. **Los tests afirman frases.** 17 archivos de test comparan con textos en español (`StatusPanelUiTest` 38 veces, `PanelUiTest` 18).
9. **El largo.** Inglés y portugués no entran igual: el seudo-idioma ya muestra que la barra del ECG del Panel Local se desborda (`05-panel-local-seudo-idioma.png`).

---

## B. Spike: cómo hacerla multilingüe

### B.1 La propuesta

**El diccionario es el de Android** (`res/values-xx/strings.xml`), el idioma lo cambia **AppCompat** (`AppCompatDelegate.setApplicationLocales`) y lo que hoy arma frases fuera de una pantalla devuelve una **clave con parámetros** (`Txt`) en vez de una frase.

Es i18n de la vieja escuela con otros nombres:

| i18n clásico | Acá |
|---|---|
| Catálogo por idioma (`.po`, `.properties`) | `res/values/strings.xml` (español, origen) y `res/values-en/`, `res/values-pt/` |
| Clave del mensaje | `name="status_connected"` → `R.string.status_connected`, verificada al compilar |
| Parámetros | `%1$s`, `%2$d`: siempre numerados, así cada idioma los ordena como quiere |
| Plurales (`ngettext`) | `<plurals>` con las reglas de cada idioma |
| Si falta la traducción | Cae sola a `values/` (español) |
| `setlocale` | `AppLocale.set(idioma)`; Android recrea las pantallas |
| Notas al traductor | Comentarios XML arriba de la entrada |
| «No traducir» | `translatable="false"` |
| Validar catálogos | Lint de Android + `StringsCatalogTest` |
| Seudo-localización | El seudo-idioma `en-XA` de Android |

Lo que se descartó:

- **Diccionario propio** (un JSON o un `Map` y `t("clave")`): habría que rehacer las 301 entradas que ya existen y se pierden los layouts con `@string`, el chequeo al compilar, los plurales y el lint.
- **La frase en español como clave** (estilo gettext): no sirve para frases con valores adentro y cualquier corrección de una tilde rompe la clave.
- **Cambiar `Locale.setDefault` y la configuración a mano**: es lo que se hacía antes de AppCompat 1.6; está obsoleto y falla con el servicio.
- **Textos servidos por la API**: se puede sumar después encima de esto; hoy no hace falta.

### B.2 Qué tiene el prototipo

- `i18n/AppLanguage.kt`: los idiomas (`es`, `en`, `pt`) y su nombre propio («Español», «English», «Português»), sin Android.
- `i18n/AppLocale.kt`: el idioma en uso, cambiarlo, y un contexto en un idioma dado para lo que no es pantalla.
- `i18n/Txt.kt` y `TxtResolve.kt`: el texto sin idioma (clave + parámetros, o un valor crudo) y cómo se resuelve.
- `res/xml/locales_config.xml`, el manifest (`localeConfig` y `autoStoreLocales`) y `resourceConfigurations` en Gradle.
- Un botón de idioma en Inicio con su diálogo.
- `StatusPanelUi` (el panel lateral) y la notificación del servicio pasados a `Txt`.
- `values-en` y `values-pt` con 66 entradas: Inicio, el panel lateral y la notificación.
- Los tests de la parte D.

### B.3 Qué se probó y qué dio

En el emulador `berry_tablet` (Android 15). La tablet real no se tocó.

| Pregunta | Resultado |
|---|---|
| ¿Cambia el idioma de toda la app desde un selector? | **Sí.** Inicio en inglés y en portugués (`01-inicio-en.png`, `03-inicio-pt.png`); el selector, `02-selector.png`. |
| ¿Convive con `UiScale` (la escala de la tablet)? | **Sí.** Con la densidad del emulador en 240 (para que `UiScale` actúe) la pantalla en portugués sale idéntica píxel por píxel a la de 320. |
| ¿Se acuerda del idioma? | **Sí**, después de cerrar la app a la fuerza y de reinstalar con `-r`. Reiniciar el emulador no se probó. |
| ¿El servicio y la notificación siguen el idioma? | **Sí, con dos cambios.** El nombre del canal cambió solo («Tótem Berry» → «Berry kiosk»). El texto no: el runtime lo volvía a mandar como frase en español cada medio segundo. Pasándolo a `Txt` y repitiéndolo en `onConfigurationChanged` del servicio, cambia en caliente. |
| ¿Qué pasa si falta una traducción? | Sale en español: en el diálogo en inglés, «Cancelar». No rompe, pero queda mezclado. |
| ¿Qué idioma toma si nadie eligió? | **El de la tablet, si la app lo tiene.** El emulador está en `en-US` y la app abrió en inglés sin que nadie lo pidiera. Ver decisión 3. |
| ¿La web del kiosco se entera? | **Sí, gratis.** Dentro del `WebView`, `navigator.language` es el idioma de la app (`pt`, `es`, `en`). La web de dev hoy responde con `<html lang="en">` en los tres casos: no lo usa. |
| ¿Qué le pasa al kiosco al cambiar el idioma? | **Android recrea la pantalla: la web se recarga** y el audio de la llamada sale y vuelve a entrar (`[LLAMADA]` en el log). Con el kiosco atrás, se recarga al volver. **En una teleconsulta cortaría la videollamada**: el selector se bloquea con una consulta en curso. |
| ¿Los modelos sin Android siguen con test en la JVM? | **Sí.** `R.string` está en los tests; un ayudante lee los XML reales y devuelve el texto en cualquier idioma. 401 tests en verde, 12 nuevos. |
| ¿Se puede frenar que entren textos nuevos al código? | **Sí.** `HardcodedTextTest` encontró 292 pendientes y 27 en layouts, y falla con uno nuevo. |
| ¿Se ve lo que falta migrar? | **Sí**, con el seudo-idioma `en-XA`: lo que pasa por el diccionario sale `[Çöñ åçéñţöš]`, lo escrito en el código sale normal. En `05-panel-local-seudo-idioma.png` se ven sin migrar la fecha, «Sin señal», «12,5», «desconectado» y «Lista». |

**Sin probar:** Android 12 o menos (no hay imagen de sistema; la tablet es Android 14), la tablet real, y derecha-a-izquierda (árabe, hebreo).

### B.4 Decisiones de Nico

1. **Qué idiomas.** **Decidido (27 nov 2026): español (origen), inglés y portugués.** Sumar otro después es una carpeta y una línea en tres lugares (un test lo controla). Un idioma que no use letras latinas (chino, árabe, ruso) necesita además otra tipografía: Barlow no las trae.
2. **Qué español.** El actual es rioplatense. Recomendado: dejarlo como está. Si la app va a otros países de habla hispana, se suma un español neutro como un idioma más.
3. **Idioma si nadie eligió.** (a) El de la tablet: es lo que hace Android solo, pero una tablet con el sistema en inglés pasaría a inglés al actualizar. (b) Español hasta que alguien elija: no sorprende a nadie en la actualización. **Decidido (27 nov 2026): la (b).**
4. **El log.** Recomendado: **siempre en español**, sea cual sea el idioma de la pantalla. Soporte lee un solo idioma y el README cita líneas de log textuales. Costo: la consola de Testing queda en español.
5. **Dónde va el selector.** Recomendado: **en Inicio, a la vista**, bloqueado con una consulta en curso. La alternativa es dejarlo detrás del PIN de Configuración (lo fija el técnico por puesto).
6. **La web del kiosco.** No entra en este plan (es `gvm.mt.totem`). El dato del spike: ya recibe el idioma en `navigator.language`; falta que la web lo use.
7. **Quién revisa las traducciones.** El agente las redacta; **los términos clínicos los tiene que revisar una persona** antes de publicar (lista en el paso 5).

---

## C. Migración

### C.1 Reglas de diseño

**Diccionario**

- `values/strings.xml` es el español y el origen. No se renombra ninguna clave que ya existe.
- Claves en minúsculas con el prefijo de su lugar, como hoy: `home_`, `local_`, `status_`, `kiosk_`, `testing_`, `check_`, `mode_`. Nuevos: `notif_` (notificación), `device_` (nombres de equipos), `rt_` (mensajes del runtime y de los enlaces), `unit_` (unidades), `fmt_` (patrones de fecha), `arrhythmia_`.
- Una entrada por uso, aunque el texto se repita («Cancelar» ya está cuatro veces): cada idioma puede necesitar una palabra distinta según dónde va.
- Lo fijo va a `values/donottranslate.xml` con `translatable="false"`: `app_name`, `ondoctor`, `II`, `x0.25`…`x2`, `mmHg`, `°C`, `SpO₂`, `YKUSH3`, `USB Audio`, `H390`, los nombres de evento.
- Las abreviaturas clínicas (`FC`, `PA`, `Resp`, `lpm`, `rpm`) llevan un comentario para quien traduce con lo que significan.

**Parámetros**

- Siempre numerados: `%1$s`, `%2$d`. `StringsCatalogTest` no deja pasar un `%s` suelto.
- **Una frase entera por entrada. No se concatena.** `"Puerto $port ${if (on) "encendido" else "apagado"}"` pasa a `rt_port_on` («Puerto %1$d encendido») y `rt_port_off`.
- Nada de insertar una palabra con su artículo: `"falta el permiso de $what"` pasa a dos entradas, una para la cámara y otra para el micrófono.
- Cantidades con `<plurals>` (`one` y `other`; en español y portugués, también `many` igual a `other`).

**`Txt`: el texto sin idioma**

Todo lo que arma un texto fuera de una pantalla devuelve `Txt`:

```kotlin
// Antes
Pair(StatusTone.OK, "Conectado")
// Después
Pair(StatusTone.OK, txt(R.string.status_connected))

// Con parámetros, y lo que no se traduce:
txt(R.string.rt_port_on, port)
raw(wifi.ssid)
```

La pantalla lo resuelve con sus recursos: `row.deviceStatus.text = device.text.resolve(context)`. Dos `Txt` iguales son el mismo texto, así que las reglas que comparaban frases comparan claves.

En el paso 4 se le suman dos formas: `Txt.Plural(id, cantidad, args)` y `Txt.Join(partes, separador)` para lo que hoy junta frases sueltas (el campo `others` de `TotemState`). Hecho en 4a: los números con decimales entran como número (`%1$.1f` en la entrada) y `resolve` los escribe con `fmt_locale` del mismo diccionario, no con el idioma de Android; `TestStrings` hace lo mismo.

**El log**

- Las líneas `[ETIQUETA] …` se quedan como literales en español.
- Cuando una línea de log lleva adentro un mensaje que también se muestra, se resuelve con un contexto fijo en español. Hecho en 4a con `i18n/LogText` (`createConfigurationContext` con `Locale("es")`, que sin `values-es` toma `values/`): `TotemRuntime` lo arma una vez y ofrece `logText(txt)`; a las clases sin Android (`HubRestState.logLine`, y en 4b `TeleStreamer`) se les pasa como función, igual que hoy reciben `log`.
- Los números del log, con `Locale.US` explícito.

**Fechas y números**

- `PanelFormat` recibe el `Locale`, no lo tiene fijo. **El idioma de los formatos sale del diccionario**, de la entrada `fmt_locale` (`es-AR` en español, `en-US` en inglés, `pt-BR` en portugués), y no de la configuración de la tablet ni de `Locale.getDefault()`: así fechas y números acompañan siempre al idioma de los textos que se ven. Si siguieran a la tablet, una tablet o el emulador en inglés mostrarían la fecha en inglés rodeada de textos en español. La pantalla lo arma una vez con `panelFormat()` (`ui/panel/PanelFormatRes.kt`, lee `fmt_locale` y los patrones con `getString`).
- Los patrones de fecha son entradas del diccionario: `fmt_long_date` = `EEEE d 'de' MMMM 'de' yyyy` en español, `EEEE, MMMM d, yyyy` en inglés; `fmt_short_date_time` = `dd/MM/yyyy HH:mm`. Así `PanelFormat` sigue sin Android y con test.
- La hora queda en 24 h en todos los idiomas.
- El decimal sale de `DecimalFormatSymbols.getInstance(locale)`, no de `replace('.', ',')`. El `12,5` del layout se pone desde el código.
- Lo que va a la API sigue con `Locale.US`. No se toca `protocol/Measurements.kt`.

**El selector**

- En la cabecera de Inicio, cuadrado (se toca), con el globo y el nombre propio del idioma.
- El diálogo, con el estilo de `ModeSwitchDialog` (el del spike es el de Material sin vestir).
- Con una consulta enviando o la teleconsulta en sala no se cambia: misma regla que el cambio de modo (`StatusSnapshot.modeSwitchBlock`), con el botón deshabilitado y el motivo a la vista.

### C.2 Los pasos

Cada paso es un PR contra `main`, uno atrás del otro (no apilados): **cada rama sale de `origin/main`** con el paso anterior ya mergeado. Después de cada uno la app queda publicable y, hasta el paso 5, **se ve exactamente igual que hoy**.

**Si el PR 46 (plan 18) entra a `main` en el medio:** el paso en curso trae `main`, regenera las listas y pasa al diccionario los textos que llegaron con él (el cartel del otoscopio en el kiosco, el porcentaje del volumen del panel lateral, los motivos de `OtoscopeReconnect`), con las mismas reglas. Nada de este plan lo espera.

En todos: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde, y `textos-en-codigo.txt` regenerado y más corto.

#### Paso 1 — La red: tests y lint (rama `feat/i18n-1-red`)

No cambia nada de la app.

1. Del parche, traer tal cual: `i18n/Txt.kt`, `i18n/TxtResolve.kt`, y de los tests `TestStrings.kt`, `HardcodedTextTest.kt`, `StringsCatalogTest.kt`.
2. Generar las listas: `I18N_REGENERAR=1 ./gradlew testDebugUnitTest --tests '*HardcodedTextTest*'`.
3. Clasificar `textos-en-codigo.txt` a mano: pasar a `## PERMITIDO` lo que no es texto (cabeceras HTTP, el JSON de `Measurements.kt`, `<unknown ssid>`, `TeleCredentials(…)`) y los motivos que sólo van al log (los argumentos de `guarded(…)`, `closeAll(…)`, `unpublishAll(…)`, `stopSession(…)`). Los de `run(…)` de `TeleStreamer` quedan pendientes: cuando algo falla terminan en «Falló …» dentro de `TeleStatus.message`, que se ve en Testing; en el paso 4 se separa el nombre de la tarea (log) del mensaje de estado. Ante la duda, queda en `## PENDIENTE`.
4. Lint en `app/build.gradle.kts`: `HardcodedText`, `SetTextI18n`, `MissingTranslation`, `ExtraTranslation`, `StringFormatMatches`, `StringFormatCount` y `MissingQuantity` como error, con `lint-baseline.xml` (se crea con `./gradlew updateLintBaselineDebug`).
5. `values/donottranslate.xml` con lo fijo; `app_name` y `ondoctor` se mudan ahí.
6. `-Pseudo` en Gradle para compilar con los seudo-idiomas (está en el parche). No va en el APK que se publica: la release es un `assembleDebug`.
7. README, sección nueva «Idiomas»: cómo se agrega un texto y qué controla cada test.

Hecho cuando: los tests nuevos pasan y fallan a propósito (agregar un `"Hola mundo"` en un `.kt` rompe `HardcodedTextTest`; sacarlo lo arregla).

#### Paso 2 — Layouts y pantallas (rama `feat/i18n-2-pantallas`)

1. Los 27 literales de layouts: a `@string/…` o a `donottranslate.xml`. La lista `## LAYOUTS` queda vacía.
2. `MainActivity` (4): los chips «Plaqueta funcional», «Comunicación con Pusher: OK». La comparación `value == "—"` se queda: son guiones, no dependen del idioma.
3. `TestingActivity` (47): cada tarjeta, con entradas enteras y parámetros. Es la pantalla con más pedazos concatenados; se reescribe `describe` por tarjeta.
4. `LocalPanelActivity`: `nibp()` deja de mirar el comienzo de la frase. Las funciones del runtime que hoy devuelven un `String` pasan a devolver un resultado con tipo (`ok` o no, más el mensaje); el mensaje sigue siendo `String` hasta el paso 4.
5. `StatusPanelView`: la descripción de la fila (está en el parche). El `"$percent %"` del volumen es del plan 18: sólo si ya está en `main`.

Hecho cuando: en el lint no quedan `HardcodedText` ni `SetTextI18n` en el baseline, y las pantallas se ven igual (capturas de antes y después en el emulador, en español).

#### Paso 3 — Los modelos sin Android (rama `feat/i18n-3-modelos`)

1. `StatusPanelUi`: del parche. `StatusSnapshot.MODE_SWITCH_BLOCKED` y `modeBlocked` también a `Txt`.
2. `PanelUi`: `Status(text: Txt, tone)`; `LEADS_OFF`, `INFLATING`, `MEASURING` como `Txt`. Sacar `removePrefix("por ")`: `OtoscopeSource.label` pasa a ser un enum (`UVC`, `CAMERA2`) y cada uso arma su entrada.
3. `PanelFormat`: `Locale` y patrones por parámetro; `ago()` con plurales; `arrhythmia()` devuelve `Txt` desde un mapa código → `R.string.arrhythmia_*` (12 entradas, **con tildes**; un código desconocido se muestra crudo).
4. `TestingSession`: `Verdict(ok, text: Txt)`.
5. Tests: cada comparación con una frase pasa por `es(…)`. Ejemplo hecho: `StatusPanelUiTest` en el parche. Un test nuevo por modelo que lea lo mismo en otro idioma (`theSameRowsInOtherLanguages`).

Hecho cuando: `ui/` no tiene nada en `## PENDIENTE` y el Panel Local se ve igual.

#### Paso 4 — El runtime y los equipos (ramas `feat/i18n-4a-runtime` y `feat/i18n-4b-equipos`)

El paso más grande y el de más riesgo: toca el servicio. Dos PR.

**4a.** `TotemRuntime`, `TotemState`, `MonitorService`, `BerryConfig.missingFields`, `HubRestState.describe`, `KnownDevices`.

- `TotemState`: `lastData`, `others`, `lastPost` y `notification` pasan a `Txt` (la notificación está en el parche).
- Las respuestas a los botones (`localStartNibp`, `testStartNibp`, `ykushSetPort`…) devuelven `Txt` dentro del resultado con tipo del paso 2.
- `Txt.Plural` y `Txt.Join`.
- `MonitorService`: del parche (`lastNotification: Txt?`, `onConfigurationChanged`). `TELE_HINT` al diccionario.
- `logText` y el contexto fijo en español.

**4b.** `tele/`, `audio/`, `otoscope/`, `usb/DeviceInventory`.

- Los motivos de `LiveKitBackend`, `StethoscopeLink`, `UvcHandle`, `ExternalCamera`, `FirstFrame` como `Txt`; el log los resuelve en español.
- `Instrument.label` y `RoomState.label` (hoy texto en el enum): fuera; quien los muestra arma su entrada.
- `CallAudioRouter.describe` y `DeviceInventory`: listas de `Txt`.
- Números del log con `Locale.US` (`StethoscopeLink.kt:319` y `:323`).

Hecho cuando: `## PENDIENTE` está **vacía**, y el log de una sesión de prueba en el emulador (`adb -s emulator-5554 logcat -s BerryMed`, o la consola de Testing) es igual al de antes: mismas líneas, mismo español.

#### Paso 5 — Idiomas y selector (rama `feat/i18n-5-idiomas`)

1. Del parche: `AppLanguage`, `AppLocale`, `AppLanguageTest`, `locales_config.xml`, el manifest y `resourceConfigurations`.
2. **Español hasta que alguien elija** (decisión 3): si no hay idioma elegido, fijar `es`. En Android 13 o más, desde `BerryMedApp.onCreate` con el `LocaleManager` del sistema; en los anteriores, en la primera pantalla con `AppCompatDelegate`. **Esto no está en el spike: hay que probarlo** (prueba M12).
3. El selector de Inicio, vestido, con el bloqueo y su regla con test (`LanguageSwitch.blocked`).
4. `values-en/strings.xml` y `values-pt/strings.xml` **completos**: `sin-traducir.txt` queda vacío. Las 66 entradas del parche sirven de arranque.
5. Glosario para la revisión clínica, en `docs/glosario-idiomas.md`, con los términos que una persona tiene que aprobar: FC, PA, SpO₂, lpm, rpm, derivaciones, electrodos sueltos, señal débil, los 12 nombres de arritmia, otoscopio, estetoscopio, fonocardiograma, manguito, tótem, maletín, kiosco.
6. El largo: recorrer todas las pantallas con `en-XA` y arreglar lo que se corta o se desborda (ya se sabe de la barra del ECG del Panel Local y del conteo del riel lateral).
7. Quitar del `KioskActivity` la línea de log «Spike idioma» si llegó del parche.

Hecho cuando: pasan todas las pruebas de la parte D en el emulador, en los tres idiomas.

#### Paso 6 — La tablet y la release

1. Con el OK de Nico y sin consulta en curso: las pruebas M de la tablet.
2. `docs/decisiones.md` (entrada del plan 19), README y `docs/hardware.md` si cambió algo.
3. Versión 1.5.0, `versionCode` 19.

### C.3 Lo que no entra

- La web del kiosco (`gvm.mt.totem`) y la API.
- Idiomas de derecha a izquierda y los que no usan letras latinas.
- Traducir el log, el README y la documentación.
- Renombrar claves que ya existen o juntar las repetidas.
- Los datos que van a la API o se guardan en la configuración.

---

## D. Pruebas

### D.1 Automáticas

Corren con `./gradlew test`, en la JVM, sin emulador. Las marcadas ✔ ya están escritas y andando en el parche.

| # | Test | Qué asegura |
|---|---|---|
| A1 ✔ | `HardcodedTextTest.noNewHardcodedTextInKotlin` | Ninguna frase nueva escrita en un `.kt`. Lo que ya no está en el código tiene que salir de la lista: sólo se achica. |
| A2 ✔ | `HardcodedTextTest.layoutsHaveNoHardcodedText` | Ningún texto con letras, ni número con decimal, escrito en un layout. |
| A3 ✔ | `StringsCatalogTest.everyEntryIsTranslatedOrListedAsPending` | Toda entrada está en todos los idiomas, o anotada como pendiente. |
| A4 ✔ | `StringsCatalogTest.translationsHaveNoEntriesOfTheirOwn` | Una traducción no tiene entradas que el español no tiene. |
| A5 ✔ | `StringsCatalogTest.whatIsNotTranslatableIsNotTranslated` | Lo marcado `translatable="false"` no aparece traducido. |
| A6 ✔ | `StringsCatalogTest.translationsKeepTheParameters` | Los mismos `%1$s`, `%2$d` en todos los idiomas, en el orden que sea. |
| A7 ✔ | `StringsCatalogTest.parametersArePositional` | Ningún `%s` sin número. |
| A8 ✔ | `StringsCatalogTest.nothingIsEmpty` | Ninguna entrada vacía. |
| A9 ✔ | `AppLanguageTest.theSelectorTheFoldersAndTheManifestAgree` | El selector, las carpetas `values-xx`, `locales_config.xml` y Gradle dicen los mismos idiomas. |
| A10 ✔ | `AppLanguageTest.matchesByLanguageNotRegion` | `pt-BR` es portugués; un idioma que la app no tiene no elige nada. |
| A11 ✔ | `StatusPanelUiTest.theSameRowsInOtherLanguages` | La misma regla leída en inglés y portugués; el nombre del Wi-Fi no se traduce. |
| A12 | `TxtTest` | Igualdad de claves, parámetros anidados, plurales, `Join`. |
| A13 | `PanelFormatTest` por idioma | `12,5` / `12.5`; la fecha larga en los tres idiomas; «hace 1 min» / «hace 5 min» con plurales. |
| A14 | `ArrhythmiaTest` | Los 12 códigos del protocolo tienen entrada en los tres idiomas; uno desconocido sale crudo; el código que va a la API no cambia. |
| A15 | `PluralsTest` | Cada `<plurals>` tiene `one` y `other` en todos los idiomas. |
| A16 | `LanguageSwitchTest` | No se cambia el idioma con una consulta enviando ni con la teleconsulta en sala. |
| A17 | `LogLanguageTest` | Con la pantalla en inglés, la línea de log de un mensaje sale en español (en `TeleStreamerTest`, con `es` como resolvedor). |
| A18 | `LintBaselineTest` | Desde el paso 2, `lint-baseline.xml` no tiene `HardcodedText` ni `SetTextI18n`; desde el 5, tampoco `MissingTranslation`. |
| A19 | Los tests que ya existen | Siguen afirmando el mismo español, ahora con `es(…)`: es la prueba de que la migración no cambió ninguna frase. `ProtocolTest` y `MetricsPosterTest` no se tocan: el cuerpo del `POST` no cambia. |

Además, `./gradlew lintDebug` en cada PR.

### D.2 Manuales, en el emulador

`emulator -avd berry_tablet -no-window -no-audio -no-boot-anim -no-snapshot-save`, y siempre `adb -s emulator-5554`. El idioma se cambia sin tocar la pantalla con:

```bash
adb -s emulator-5554 shell cmd locale set-app-locales com.guardiavirtual.berrymed --locales pt
```

| # | Prueba | Se espera |
|---|---|---|
| M1 | En Inicio, elegir cada idioma con el selector | Inicio, Panel Local, Configuración, Testing y el panel lateral, enteros en ese idioma. Captura por pantalla e idioma. |
| M2 | Cerrar la app a la fuerza, reinstalar con `-r`, reiniciar el emulador | Abre en el idioma elegido. |
| M3 | `adb … shell dumpsys notification --noredact`, antes y después de cambiar | El texto y el nombre del canal, en el idioma nuevo, sin reabrir la app. |
| M4 | Abrir el kiosco, volver a Inicio, cambiar el idioma, volver al kiosco | La web se recarga una vez. |
| M5 | `adb … shell wm density 240`, repetir M1, y `wm density reset` | Mismo tamaño que a 320; todo en el idioma. |
| M6 | Compilar con `-Pseudo`, `--locales en-XA`, recorrer todas las pantallas | Todo texto sale entre corchetes y con acentos, salvo datos (red Wi-Fi, números, modelos). Nada cortado ni encimado. |
| M7 | Panel Local en cada idioma | Fecha larga, decimales y «hace N min» como corresponde al idioma; hora en 24 h. |
| M8 | Con la app en inglés, `adb … logcat -s BerryMed` y la consola de Testing | Las líneas, en español, iguales a las de antes. |
| M9 | Testing → Copiar JSON, en español y en inglés | El mismo JSON, con punto decimal. |
| M10 | Compilar el APK normal y `aapt2 dump badging` | `locales:` sólo `es`, `en`, `pt`. |
| M11 | Cada idioma, todas las pantallas, contra el glosario | Lo revisa una persona: términos clínicos y tono. |
| M12 | Instalación limpia con el emulador en `en-US` | Abre en español (decisión 3). |

### D.3 Manuales, en la tablet (con el OK de Nico)

| # | Prueba | Se espera |
|---|---|---|
| T1 | Actualizar de 1.4.0 a 1.5.0 | Sigue en español, con la configuración intacta. |
| T2 | Elegir inglés y reiniciar la tablet | Inicio y la notificación en inglés al encender. |
| T3 | Teleconsulta de prueba en inglés | El selector está bloqueado durante la consulta; la videollamada no se corta. |
| T4 | Panel Local en portugués con los equipos | Estados y mensajes del otoscopio y del estetoscopio en portugués. |
| T5 | El log de T3 y T4 (`logcat -s BerryMed`) | En español. |

---

## Entrega (para el agente)

- Un PR por paso, contra `main`, en orden. Cada rama sale de `origin/main`, nunca de otra rama sin mergear. No apilar ramas.
- Commits en español con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commiteá este plan y el parche en el primer PR. No commitees `.claude/`.
- El parche es material de consulta: no se aplica entero. Cada paso dice qué traer.
- El emulador se puede levantar sin ventana para las pruebas M. **La tablet real (`192.168.1.81:5555`) no se toca**: las pruebas T las hace la sesión principal.
- Informe final de cada paso: qué entradas se agregaron, qué frases cambiaron de redacción y por qué (toda frase que salió de `textos-en-codigo.txt` tiene que estar igual en `strings.xml`, salvo las que se partieron por la regla de no concatenar), cuánto bajó cada lista, y el resultado de gradle y del lint.
