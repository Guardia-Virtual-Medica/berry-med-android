# Plan 10: el estetoscopio del Panel Local y la videollamada suenan por el jack de la tablet

Plan para un agente (2 nov 2026). Pedido del usuario: que el sonido del Panel Local salga por defecto por el **jack analógico de la tablet** (auriculares con cable en la ficha de 3,5 mm), no por el auricular USB H390. Ampliado el mismo día: **la videollamada también va por el jack** cuando hay algo enchufado, y la configuración sigue dejando elegir el parlante de la tablet. Sin cambios en la API ni en el kiosco (`gvm.mt.totem`). Todo es en `app/src/main/java/com/guardiavirtual/berrymed/audio/` y en los textos del Panel Local.

## Qué pasa hoy

- Lo único que suena en el Panel Local es el estetoscopio (la escucha en vivo y las grabaciones). `StethoscopeLink.devices()` elige la salida con `AudioPick.headsetOutput`: una salida **USB** cuyo nombre contenga el configurado ("H390") o, si no, "Logitech". Si no está, no suena: muestra la onda y dice "Sin auricular".
- Un auricular en el jack de la tablet lo lista Android como `TYPE_WIRED_HEADSET` (con micrófono) o `TYPE_WIRED_HEADPHONES` (sin micrófono), **sólo mientras está enchufado**. Testing → Dispositivos que ve Android ya los muestra como "auricular(es) con cable (jack)". Como no son USB, hoy `headsetOutput` nunca los elige.
- La videollamada tiene su propio selector (Auricular H390 o Parlante de la tablet, plan 7) y no cambia con este plan.

## Decisiones

- **Regla automática, sin opción nueva en la configuración**: si hay algo enchufado en el jack de la tablet, el estetoscopio suena por ahí; si no, por el auricular USB H390 como hasta ahora. Nunca por el parlante de la tablet (se acopla) ni por el adaptador del estetoscopio.
- La salida se elige **al tocar Escuchar** (o al reproducir una grabación), como hoy: enchufar o desenchufar el jack en plena escucha no la cambia; hay que Parar y Escuchar de nuevo. Se dice en el KDoc, no en pantalla.
- El micrófono del auricular del jack **nunca** hace de estetoscopio: `stethoInput` sólo elige entradas USB, y queda un test que lo diga.
- Los puertos del YKUSH3 no cambian: Escuchar sigue encendiendo el del adaptador y el del auricular si están apagados (el H390 encendido no molesta y lo necesita la videollamada). Lo único que cambia solo es la espera de hasta 5 s: con el jack ya puesto, la salida aparece al instante y se espera sólo al adaptador.
- **La videollamada también**: con la opción **Auricular** del paso Kiosco, la llamada sale por el jack de la tablet si hay algo enchufado, si no por el H390 y, si tampoco, por el parlante (nunca se pierde el audio). La opción **Parlante de la tablet** sigue existiendo y sigue mandando sólo la llamada al parlante; el estetoscopio nunca va al parlante. No hay opción nueva: el valor guardado `auricular` pasa a significar "auriculares, jack primero". Ver la parte 2.
- Si la tablet no tiene ficha de 3,5 mm y se usa un adaptador USB-C a jack, Android lo lista como auricular USB con su propio nombre: eso ya anda hoy poniendo ese nombre en **Nombre del auricular**. No hace falta nada nuevo, pero hay que decirlo en el README.

## Diseño

1. **`audio/AudioPick.kt`**: constantes sin Android `TYPE_WIRED_HEADSET = 3` y `TYPE_WIRED_HEADPHONES = 4` (como ya está `TYPE_BUILTIN_SPEAKER = 2`). Función nueva `stethoOutput(devices, headsetName): Device?`: primero una salida (`isSink`) de tipo jack, en el orden en que las lista Android; si no hay, `headsetOutput(devices, headsetName)`. Actualizar el KDoc del objeto ("por cuál suena").
2. **`audio/StethoscopeLink.kt`**: `devices()` usa `stethoOutput` en vez de `headsetOutput`. La línea `[ESTETO] Escuchando por …, suena por …` agrega entre paréntesis por dónde: `(jack de la tablet)` o `(auricular USB)`; para eso `Devices` o `devices()` tienen que decir el tipo (por ejemplo un `Boolean viaJack` o devolver también el `Device` elegido). Los mensajes "Sin auricular…" de `start`/`play` y el `notes += "Sin auricular: sólo se ve la onda."` de `TotemRuntime.startStethoscope` pasan a: "Sin auriculares: nada en el jack de la tablet ni el auricular USB. Sólo se ve la onda." y "Sin auriculares: las grabaciones no salen por el parlante de la tablet." KDoc de la clase y de `play` acorde.
3. **`DeviceInventory.isUsb`** no cambia. Si hace falta saber "es jack" en más de un lugar, una `isJack(type)` al lado.
4. **Textos** (`values/strings.xml`): `help_call_audio` = "El estetoscopio del Panel Local sale por el jack de la tablet y, si no hay nada enchufado ahí, por el auricular USB."; `help_stetho`: sumar que el auricular del jack de la tablet tiene prioridad y no necesita nombre. Mismo tono que el resto (voseo, sin tecnicismos).
5. La teleconsulta (`tele/LiveKitBackend.kt`) sólo publica el estetoscopio, no lo reproduce: no cambia. `HubRestState` y `CallAudioRouter` tampoco.

### Parte 2: la videollamada por el jack (`audio/AudioPick.kt`, `audio/CallAudioRouter.kt`)

1. `AudioPick.callOutput(devices, choice, headsetName)`: con `CALL_AUDIO_SPEAKER`, el parlante (como hoy). Con auricular: primero una salida de tipo jack (`TYPE_WIRED_HEADSET` o `TYPE_WIRED_HEADPHONES`, la misma regla que `stethoOutput`), después el H390 por nombre, después el parlante. Nunca el adaptador del estetoscopio. Hecho sobre la lista de `getAvailableCommunicationDevices()`, que incluye los auriculares del jack (con auriculares sin micrófono Android usa el micrófono de la tablet: el micrófono acompaña, como hoy).
2. `CallAudioRouter`: `label()` devuelve "el jack de la tablet" para los tipos jack (el `productName` de esos dispositivos suele venir vacío y hoy mostraría "tipo 3"). `announce()` y `describe()`: la línea de respaldo pasa a "Nada en el jack ni el auricular USB: la llamada sale por el parlante". Actualizar el KDoc de la clase (el estetoscopio ya no "sale siempre por el auricular": sale por el jack o el H390).
3. `HubRestState` no cambia: con Auricular el puerto del H390 sigue encendido en reposo, porque es el respaldo cuando no hay nada en el jack.
4. Chromium, en modo comunicación, ya prefiere por sí mismo el auricular con cable antes que el USB: Berry Med sólo lo confirma y lo reafirma. Decirlo en el KDoc.
5. **Textos** (`values/strings.xml`): `call_audio_headset` pasa a "Auriculares (jack de la tablet o USB)"; `help_call_audio` = "Con Auriculares, la llamada y el estetoscopio salen por el jack de la tablet y, si no hay nada enchufado ahí, por el auricular USB. Con Parlante sólo la llamada va al parlante: el estetoscopio sigue por los auriculares."

## Tests

- `AudioPickTest.kt`: con auriculares en el jack y el H390 a la vez gana el jack; auricular con cable (con micrófono) también gana; sin jack, el H390; sin ninguno, null (nunca el parlante ni el adaptador, aunque sea la única salida); el micrófono del auricular del jack nunca es la entrada del estetoscopio (`stethoInput` con una entrada `TYPE_WIRED_HEADSET`, `usb = false`, da null).
- `AudioPickTest.kt`, `callOutput`: con Auricular y auriculares en el jack más el H390, gana el jack; con Parlante elegido, el parlante aunque esté el jack; sin jack, el H390; sin nada, el parlante; el adaptador nunca.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.

## Verificación

El emulador `berry_tablet` (`emulator-5554`) no tiene jack ni placas USB: ahí sólo se verifica que compila y que el Panel Local sigue diciendo que no aparece el adaptador. **Decirlo, no darlo por probado.** En la tablet: con auriculares en el jack, Testing → Dispositivos que ve Android los lista como "auriculares con cable (jack)"; Escuchar suena por ellos y el log dice `suena por … (jack de la tablet)`; reproducir una grabación también; al desenchufarlos y volver a Escuchar, suena por el H390. Y la videollamada: con el jack puesto y Auriculares elegido, Testing → Teleconsulta tiene que mostrar "el jack de la tablet" como dispositivo de comunicación durante una llamada; al desenchufar, el H390; con Parlante elegido, el parlante.

## Documentación

- README: Panel Local (estetoscopio: por dónde suena y en qué orden), la tabla del hardware (auriculares por el jack de la tablet), Configuración → Kiosco (la opción Auriculares y su ayuda, y el párrafo "Audio de la videollamada" de la teleconsulta: jack primero, H390, parlante) y la tabla de problemas ("El estetoscopio no suena" y "No se escucha la videollamada": revisar el jack y el H390). Nota sobre el adaptador USB-C a jack.
- `docs/hardware.md`: sección breve "Auriculares por el jack de la tablet" (tipos de Android, sólo aparecen enchufados, prioridad sobre el H390 para el estetoscopio) y en "Cómo se elige la salida de la llamada": una fila para el jack (con auriculares enchufados, tanto en `MODE_NORMAL` como en modo comunicación la llamada va al jack: es lo buscado). En "Riesgos a confirmar en la tablet real": que `setCommunicationDevice` acepte los auriculares del jack y que el micrófono acompañe (el del auricular con cable si tiene, si no el de la tablet).
- `docs/decisiones.md`: entrada con estas decisiones.
- Commitear este plan.

## Entrega

Rama `feat/estetoscopio-jack-tablet` desde `main`, commits en español en el estilo del repo, PR contra `main`. Independiente del plan 9 (otro directorio, otra rama). No cambiar la versión (`versionCode`/`versionName`). No tocar `local.properties`, `docs/capturas/` ni `docs/apk/`.
