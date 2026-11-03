# Plan 13: el termómetro va en el puerto 3 del YKUSH3, siempre encendido

Plan para un agente (3 nov 2026). El usuario y Mauricio validaron el armado nuevo del maletín:
- tablet → dock USB-C;
- del dock salen la plaqueta PM6750 y el hub YKUSH3 (alimentado);
- puerto 1 del hub: otoscopio;
- puerto 2 del hub: adaptador de audio con el estetoscopio en su jack;
- **puerto 3 del hub: termómetro IR**.

**El H390 ya no se usa**: los auriculares van al jack de la tablet (plan 10). El termómetro tiene que estar **siempre encendido**, con o sin kiosco, en el Panel Local y en la videollamada, para que las lecturas lleguen en los dos. Sin cambios en la API ni en el kiosco web.

## Qué pasa hoy

- `BerryConfig` tiene `ykushOtoscopePort = 1`, `ykushStethoscopePort = 2` y `ykushHeadsetPort = 3`. Se guardan en `ConfigStore` con las claves `ykush_otoscope_port`, `ykush_stethoscope_port` y `ykush_headset_port`, y se cargan en el paso Plaqueta (`activity_main.xml`: `ykushHeadset`, "Puerto del auricular").
- `HubRestState.wanted(config, kiosk)`:
  - El puerto del auricular queda encendido sólo si `callAudioOutput == CALL_AUDIO_HEADSET`. Con "Parlante" se **apaga**, y con el termómetro en el 3 se quedaría sin energía.
  - Otoscopio y estetoscopio siguen al kiosco (plan 12).
- `applyHubRest()` no hace nada mientras `hubRestBlocked()`: consulta enviando, Testing, Panel Local o teleconsulta usando un equipo. Si en ese momento el puerto 3 estaba apagado, el termómetro sigue sin energía hasta que se libera.
- `TotemRuntime.startStethoscope` enciende `listOf(cfg.ykushStethoscopePort, cfg.ykushHeadsetPort)` y apaga al salir los que reclamó (`PanelPorts`).
- `ykushPortRoles()` y `ykushPortsShared()` usan los tres puertos de hoy.

## Decisiones

1. **Configuración**:
   - Sale `ykushHeadsetPort`, entra `ykushThermometerPort` (de fábrica `3`), con la clave nueva `ykush_thermometer_port`.
   - En el paso Plaqueta, "Puerto del auricular" pasa a ser **"Puerto del termómetro"**.
   - `ykushPortRoles()` dice "termómetro" y `ykushPortsShared()` compara otoscopio, estetoscopio y termómetro.
   - **Migración**: una configuración guardada sin la clave nueva carga el termómetro en `3`. La clave vieja `ykush_headset_port` se ignora al leer y se borra al guardar. Test en `ConfigStore` o en lo que lo cubra hoy.
2. **El puerto del termómetro siempre encendido**:
   - `HubRestState.wanted` lo da `true` en las dos ramas (con y sin kiosco) y no depende de `callAudioOutput`.
   - El H390 deja de tener puerto. La salida de audio de la llamada ya no cambia ningún puerto: sacar el `hubRest.again()` que dispara ese cambio, si es sólo por eso.
3. **Encender nunca espera**:
   - Aunque `hubRestBlocked()` sea true, `applyHubRest()` igual manda las órdenes de **encender** que tocan al puerto del termómetro (encender no le saca nada a nadie). Las de apagar siguen esperando como hoy.
   - Sacá la regla a `HubRestState` (por ejemplo `changes(wanted, states, blocked)`, que con `blocked` devuelve sólo los encendidos del termómetro), con test.
   - Si el hub se reconecta (otro `deviceId`), también.
4. **Nadie apaga el puerto del termómetro**:
   - `startStethoscope` deja de encender el puerto del auricular: enciende sólo el del estetoscopio.
   - `PanelPorts` del Panel Local y de la teleconsulta nunca reclaman ese puerto.
   - En Testing → Hub YKUSH3, **Apagar** en el puerto del termómetro sigue andando (es una prueba a mano), pero la fila dice "termómetro · se vuelve a encender solo" y el servicio lo vuelve a encender en la vuelta siguiente del `watchLoop`.
5. **Guardar este arranque en el hub** (0x41) guarda el termómetro encendido.
6. **Audio**: la salida de la llamada y del estetoscopio sigue la regla del plan 10. Primero el jack de la tablet. Después un auricular USB por nombre (`headsetName`): queda como respaldo inofensivo, no se borra la lógica, pero el texto de la configuración ya no nombra al H390 como parte del armado. Para la llamada, al final el parlante; el estetoscopio nunca va al parlante.
   - Ajustá `call_audio_headset` / `help_call_audio` y las ayudas que nombran al H390 en el hub.
   - El valor guardado de `callAudioOutput` no cambia.
7. **Lecturas del termómetro**: el `ThermometerLink` y el envío no cambian. Sólo hace falta que tenga energía. Revisá que, si el termómetro aparece después (porque el puerto se encendió tarde), el link lo tome solo, con su permiso USB (el filtro ya incluye los fabricantes de los chips serie). Si hace falta algo, decilo.

## Tests

- `HubRestStateTest`:
  - el termómetro encendido con y sin kiosco, con "Auriculares" y con "Parlante";
  - con `blocked`, sólo el encendido del termómetro;
  - un puerto libre en `null`.
- Migración de la configuración: sin la clave nueva → termómetro en 3.
- `BerryConfigTest` (o el que cubra roles y puertos repetidos).
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug` en verde.

## Verificación

El emulador `berry_tablet` no tiene hub ni termómetro: ahí sólo se ve el paso Plaqueta con "Puerto del termómetro" en 3 después de la migración, y el log. **Decirlo, no darlo por probado.**

En la tablet:
- las luces del hub muestran el puerto 3 encendido siempre (kiosco, configuración, Testing, Panel Local);
- el termómetro mide en el Panel Local y, en una consulta, la medición llega a OnDoctor.

## Documentación

- README:
  - tabla de conexiones y armado: termómetro en el puerto 3, sin H390, auriculares al jack;
  - paso Plaqueta;
  - Hub YKUSH3;
  - tabla de problemas: "el termómetro no mide": revisar el puerto 3 y su luz.
- `docs/hardware.md`: tabla de conexiones y el árbol (el H390 sale), riesgos.
- `docs/decisiones.md`: entrada con esta decisión, validada con Mauricio el 3 nov 2026.
- Commitear este plan.

## Entrega

- Rama `feat/termometro-puerto-3` desde `main`, PR contra `main`.
- Commits en español en el estilo del repo, con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- No cambiar la versión.
- No tocar `local.properties`, `docs/capturas/` ni `docs/apk/`.
