# Análisis de la documentación (Fable, 29 oct 2026)

> Nota: escrito el 29 de octubre de 2026 sobre el commit `2883e0c` (`main` + PR #12). Los hallazgos 1 a 7 de la revisión de código se corrigieron en el PR #13; los números de línea son históricos.

Qué documentación tiene el proyecto hoy, qué promete que el código no cumple, y qué falta. Complementa `revision-codigo-opus.md` (hallazgos de código); donde un punto sale de ese informe se cita su número.

## Qué documentación hay

| Dónde | Qué es | Se commitea |
|---|---|---|
| `README.md` (150 líneas) | La única documentación del repo: cómo funciona, equipos, recuperación, configuración, instalación, Testing, Panel Local, compilar. | Sí |
| KDoc y comentarios en el código | Explican el porqué de cada decisión; es la documentación técnica real. | Sí |
| `docs/planes/plan-1..5*.md` | Los planes de cada etapa del Panel Local, con decisiones de alcance. | **No** |
| `docs/redisenio/` | El handoff de diseño hi-fi del Panel Local (README, prototipos, capturas). | **No** |
| `docs/revisiones/` | Este análisis y la revisión de código. | **No** |
| `docs/capturas/`, `docs/apk/` | Capturas del emulador y el APK compilado. | No (y está bien) |
| Fuera del repo | El manual del estetoscopio Dalia, los volcados USB del otoscopio y del adaptador (`datos usb *.txt`), el croquis de Mauricio del armado, la memoria de esta sesión de trabajo. | No |

## Hallazgos

### D1. La memoria del proyecto vive sólo en esta Mac
Los planes, el handoff de diseño, los volcados USB y las decisiones de alcance (presencial sin teleconsulta, PIN, nada se guarda, LiveKit después, etc.) no están en ningún repositorio. Si se pierde esta computadora o alguien más toma el proyecto, el README no alcanza para saber por qué el otoscopio tiene dos caminos, por qué el panel no apaga un puerto que ya estaba encendido, o qué quedó pendiente.
**Qué haría:** commitear `docs/planes/`, `docs/redisenio/` (son 1 MB, sin el APK ni las capturas) y un `docs/hardware.md` con los VID/PID, los volcados USB resumidos, el croquis del armado y los nombres de audio que declara cada placa. Y un `docs/decisiones.md` corto con las decisiones de producto y la fecha. El `.gitignore` excluiría sólo `docs/capturas/` y `docs/apk/`.

### D2. El README promete que el tope de 30 minutos frena el envío, y no lo frena
Punto 5 de "Cómo funciona": "sin ella la tablet seguiría enviando hasta el próximo inicio". Con el código actual, al vencer el tope la prueba de 10 s reabre la consulta si la plataforma sigue con la toma abierta (revisión de código, hallazgo 1). O se corrige el código o se corrige el README; lo que no puede quedar es la promesa.

### D3. "Si falta alguno, sigue con los demás" no vale para el termómetro solo
Punto 2 de "Cómo funciona" y punto 7 ("La última lectura reemplaza la temperatura de la plaqueta"). Con la plaqueta caída, una lectura del termómetro no se envía (hallazgo 2). El README debería decir que el termómetro viaja dentro del paquete de la plaqueta, o el código debería mandarla sola.

### D4. "La URL base tiene que empezar con `https://`", pero el código acepta `http://`
Sección "Configuración" (línea 70) contra `hasWebScheme` y `usesCleartextTraffic="true"` (hallazgo 21). Decidir una de las dos.

### D5. La instalación promete que el permiso USB del otoscopio se acepta "con Usar de forma predeterminada"
Punto 2 de "Instalar y dejarla sola". Para un dispositivo de clase video, Android 10 o más puede negar el permiso sin diálogo si la app todavía no tiene el permiso de cámara (hallazgo 12, a confirmar en la tablet). El README debería decir: abrir el Panel Local una vez y aceptar la cámara **antes** de aceptar el permiso USB del otoscopio, o que el instalador lo haga desde la pantalla principal.

### D6. Testing con el Panel Local abierto
"Con una consulta enviando no se cambia ningún puerto" (sección Testing) no cubre el panel abierto: desde Testing se puede apagar un puerto que el panel está usando (hallazgo 15). Documentar la regla que se decida.

### D7. Falta una guía de prueba con el hardware
El README dice cómo instalar y cómo probar con Chrome adelante (punto 6), pero no cómo validar el Panel Local con el armado completo. Esa lista existe sólo en el chat: Testing → Dispositivos (las dos placas de audio y si el otoscopio aparece como cámara externa), plaqueta en el panel, estetoscopio (acople, ganancia, onda), otoscopio (luz, imagen, camino Camera2 o UVC, ampliar, apagar, puerto como estaba), y qué mirar en `adb logcat -s BerryMed` si algo falla. Va como sección "Probar el Panel Local en el tótem".

### D8. "Compilar" se queda corto
No dice que el JDK 17 hay que apuntarlo con `JAVA_HOME` en Mac (`/opt/homebrew/opt/openjdk@17`), ni cómo está armado el emulador de prueba (`berry_tablet`, Android 15, 2560×1600 a 320 dpi = 1280×800 dp), ni que `local.properties` no se commitea. Tampoco que el APK no se publica en GitHub (no hay releases ni CI) y cómo se instala en la tablet (`adb install -r`, misma clave de firma).

### D9. Configuración muerta sin documentar
`BerryConfig` tiene `apiEndpoint`, `apiUsername` y `apiPassword` que el formulario no muestra y que cada guardado pisa (hallazgo 20). El README no los menciona; conviene sacarlos del código para que la configuración documentada sea la real.

### D10. KDoc desactualizado
`DeviceInventory` habla de "antes de escribir el Panel Local"; el KDoc de `localStethoStart` tiene un salto de línea roto (hallazgo 26). El KDoc de `PanelPorts` dice que encender de más "no rompe nada", pero sí deja un puerto como del panel (hallazgo 3).

### D11. La sección "Panel Local" del README la reescribe el plan 5
Está al día con el código de hoy (incluido Ampliar del PR #12), pero el rediseño la cambia entera: cabecera, dock, dispositivo abierto, tira de ECG, Finalizar, capturas y grabaciones en memoria. Lo cubre la etapa 5a; no hace falta tocarla antes.

## Lo que está bien

- El README está escrito para quien instala y opera el tótem, no para el que programa: explica qué hace la app ante cada falla ("Cómo se recupera sola") y qué no hace. Eso es raro y vale.
- Los comentarios del código explican el porqué (límite de Pusher, por qué `connectedDevice`, por qué el relé ya no va, por qué el tope de 1440² del otoscopio) y están en el mismo idioma que el README.
- Las tablas de equipos con VID/PID y de eventos de Pusher son exactas respecto del código.

## Qué haría primero

1. Commitear `docs/planes/`, `docs/redisenio/` y escribir `docs/hardware.md` y `docs/decisiones.md` (D1).
2. Corregir en el README las tres promesas falsas (D2, D3, D4) junto con los arreglos de código correspondientes, para que queden coherentes.
3. Agregar "Probar el Panel Local en el tótem" (D7) antes de la prueba con hardware, así quien la hace tiene la lista.
