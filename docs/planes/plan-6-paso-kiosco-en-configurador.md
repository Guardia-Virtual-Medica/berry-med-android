# Plan 6, paso "Kiosco" en el configurador

Plan para un agente (29 oct 2026). Desde el PR #20 el kiosco corre dentro de Berry Med y hay que dejar la tablet lista con varios pasos que hoy sólo están en el README. El usuario pidió sumarlos al configurador como un paso más.

## Cómo está hoy

- `MainActivity` + `activity_main.xml`: un asistente de tres pasos (`stepDestination`, `stepOrders`, `stepDevice`) en un `ViewFlipper` `@+id/steps`, con Atrás / Siguiente y **Guardar** en el último. `showStep(index)` hace `coerceIn(0, 2)`, `missingField(step)` valida cada paso y las etiquetas de arriba son tocables.
- El campo **URL del kiosco** (`@+id/kioskUrl`) está en el paso Destino.
- `MonitorService.promote`, `runtime.foregroundTypes`, `ForegroundAccess` dicen si el servicio tiene cámara y micrófono.
- `KioskActivity` es la app de inicio (`HOME` + `DEFAULT`).

## Qué agregar

Un cuarto paso, **Kiosco**, después de Equipo. Guardar pasa a ese paso (el último). Las etiquetas, `showStep` y la validación se generalizan a cuatro pasos (sin números mágicos: el tope sale de la cantidad de hijos del `ViewFlipper`).

Contenido del paso, de arriba abajo, con el estilo del formulario (`FormTitle`, `Help`, `Field`):

1. **URL del kiosco**: el campo `kioskUrl` se mueve acá desde Destino (mismo id, misma validación: vacío o una URL `http(s)` válida). Ayuda: "La misma dirección que hoy abre Chrome en esta tablet, con el nombre de la organización al final."
2. **Lista de puesta en marcha**: cada ítem es una fila con un punto de estado (verde hecho, ámbar falta), un texto y, si corresponde, un botón. El estado se recalcula en `onResume` y cada vez que se muestra el paso.
   - **Cámara y micrófono**: hecho si los dos permisos están otorgados. Botón "Dar permisos" (pide los que faltan; si Android ya no muestra el diálogo, abre los ajustes de la app).
   - **Berry Med como app de inicio**: hecho si `packageManager.resolveActivity(Intent(ACTION_MAIN).addCategory(CATEGORY_HOME), MATCH_DEFAULT_ONLY)` resuelve a este paquete. Botón "Elegir app de inicio" (`Settings.ACTION_HOME_SETTINGS`; si no existe, `Settings.ACTION_SETTINGS`).
   - **Servicio con cámara y micrófono para la teleconsulta**: hecho si `runtime.foregroundTypes` tiene los dos (en Android < 11 siempre hecho). Sin botón: se resuelve solo con esta pantalla adelante y los permisos dados; si falta, el texto lo dice.
   - **Sin optimización de batería**: el mismo estado y la misma acción que el botón que ya existe arriba (reusar, no duplicar la lógica).
   - **Configurar el tótem en el kiosco**: no se puede detectar. Texto: "La primera vez el kiosco pide el identificador y la contraseña del tótem: Berry Med no usa la sesión de Chrome." Botón "Abrir el kiosco" (abre `KioskActivity`; deshabilitado si la URL está vacía o sin guardar).
3. **Fuera de la tablet** (sólo texto, ítems sin estado):
   - "En OnDoctor, el tótem tiene que tener habilitados el otoscopio o el estetoscopio; si no, el médico no los ve."
   - "Si algo abre Chrome al prender la tablet (modo kiosco, app de administración), hay que sacarlo: Chrome no se usa más para el kiosco."
   - "Para volver acá desde el kiosco: cinco toques arriba a la izquierda y el PIN del Panel Local."

La lógica de "qué ítem está hecho" va en una función pura (por ejemplo `ui/KioskChecklist.kt`, con un `data class` de entradas booleanas y una lista de ítems con estado) para testearla sin Android. La pantalla sólo junta las entradas y pinta.

## Tests

- `KioskChecklist`: cada combinación relevante de entradas da los estados esperados; el servicio en Android < 11 cuenta como hecho; "Abrir el kiosco" deshabilitado sin URL.
- La validación de la URL del kiosco sigue pasando en su nuevo paso.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde (hoy 224).

## Verificación

En el emulador `berry_tablet` (`emulator-5554`, adb en `~/Library/Android/sdk/platform-tools/adb`), capturas del paso Kiosco con ítems en ámbar y en verde (por ejemplo, antes y después de elegir Berry Med como app de inicio y dar permisos). Capturas en `docs/capturas/` (no se commitean). Para llegar a la configuración desde el kiosco: cinco toques arriba a la izquierda y el PIN `1515`.

## Documentación

README: la sección de instalación del kiosco remite al paso Kiosco del configurador en lugar de repetir la lista entera. Commitear este plan.
