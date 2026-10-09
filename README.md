# Berry Med para Android

App de tótem que reemplaza a `berry-monitor.exe`. Con la plaqueta Berry conectada por USB, lee las tramas del PM6750, las interpreta y, mientras la sesión está activa, hace un `POST` por segundo a `{API_URL}/{TOTEM_ID}/metrics` con el mismo JSON que el monitor de Windows.

Pusher no transporta las mediciones. La tablet se suscribe al canal público y espera `start-monitoring`, `stop-monitoring` y `start-blood-pressure`.

## Qué necesita la tablet

- Android 8 o superior, con USB en modo host (OTG).
- Un hub USB con alimentación. La plaqueta, el termómetro infrarrojo y el relé `USBRelay2` son tres dispositivos distintos.
- La plaqueta se reconoce porque manda tramas `55 AA` a 115200 baudios. Es un STM32 VCP (`0483`). El relé es el HID `16C0:05DF`.
- El termómetro manda una línea JSON con `valor_temperatura_ir` sólo cuando alguien mide, así que no se lo reconoce por lo que transmite: toma el puerto serie que no es la plaqueta y lo deja abierto esperando. Un puerto de ST nunca va al termómetro.

Si el puerto de ST abre y no llegan tramas, si no abre o no acepta la secuencia de arranque tres veces seguidas, o si la plaqueta se queda muda 20 segundos, la app corta los 12 V durante 5 segundos con el relé y vuelve a conectar. Entre un corte y el siguiente espera al menos un minuto, para no cortarla mientras arranca. Si se desenchufa el cable no toca el relé enseguida (ver abajo los 3 minutos), y si el que no contesta es otro puerto, no lo toca nunca. Si un corte no repuso la alimentación (falló el relé o la app se cerró en medio), mientras no aparezca la plaqueta se le pide al relé una vez por minuto que deje pasar los 12 V. Si la plaqueta no aparece en el USB durante 3 minutos (puede pasar que tenga alimentación y no llegue a enumerar), se corta igual, y mientras siga sin aparecer como mucho cada 30 minutos: con el relé enchufado y la plaqueta apagada a propósito, el relé conmuta cada media hora. El relé recibe 8 bytes `[comando, relé, 0…]`, que es lo que llega al dispositivo cuando el exe manda sus 9 con el report ID 0, y cada cambio se confirma leyendo su estado.

## Configuración

En la pantalla van la URL base, el tótem, el usuario y la contraseña, la clave y el cluster de Pusher, el canal público y los nombres de los eventos de inicio y fin. Un canal `private-` o `presence-` no se conecta: la app no autentica canales y la pantalla lo marca como dato faltante. También la ganancia y el modo de ECG, la ganancia de respiración y el modo de presión. Se guardan en el dispositivo.

La sesión se corta sola a los 30 minutos si no llega el evento de fin. `0` desactiva ese tope.

La ganancia y el modo de ECG se mandan a la plaqueta apenas se guardan, sin reconectar.

## Dejar la tablet sola

La configuración queda guardada en la tablet y el servicio arranca solo al encenderla y después de instalar una versión nueva. Para que no haga falta tocar nada más:

1. **Permiso USB con "Usar de forma predeterminada".** Android da el permiso por cada conexión del dispositivo: después de un reinicio o de un corte del relé la plaqueta vuelve como un dispositivo nuevo. Al aceptar el permiso de la plaqueta, el termómetro y el relé, marcar esa casilla. Así Android abre la app y le da el permiso solo cada vez que aparecen. Si alguien rechaza o cierra el pedido, la app lo vuelve a pedir a los 5 minutos. Si el pedido quedó tapado por otra pantalla sin respuesta, no se repite solo (apilaría diálogos): la pantalla dice "Falta aceptar el permiso USB" y se resuelve con **Pedir permiso USB**. Un dispositivo sin permiso no frena a los demás. Con la casilla marcada, Android además abre la app cada vez que el dispositivo aparece, también después de cada corte del relé: la tablet tiene que ser sólo para el tótem.
2. **"Evitar que se duerma"** en la pantalla, para excluir la app de la optimización de batería. Algunas marcas tienen además su propia lista de apps que se duermen: sacarla de ahí también.
3. **Sin bloqueo con PIN, patrón ni contraseña.** Con bloqueo seguro Android no avisa el encendido hasta que alguien desbloquea, y la app no arranca.
4. **Instalar siempre un APK firmado con la misma clave.** Si cambia la clave hay que desinstalar, y eso borra la configuración.
5. **Probarlo**: reiniciar la tablet con todo enchufado y, con la sesión andando, forzar un corte del relé. En los dos casos la plaqueta tiene que volver sola, sin tocar la pantalla.

Si Pusher se cae, la librería reintenta unos 85 segundos y se rinde. Treinta segundos después la app le pide otra tanda de intentos, y así mientras no haya conexión. Si queda más de 5 minutos conectando sin respuesta (una red que acepta la conexión y no contesta), se arma un cliente nuevo; cada vez espera el doble, hasta una hora.

El servicio corre siempre como `connectedDevice`. `dataSync` no sirve: en Android 15 no puede arrancar al encender la tablet y tiene un tope de 6 horas por día.

## Qué no hace esta versión

No publica `/health` y no usa Bluetooth. La aplicación web no cambia: cuando el `POST` llega, sigue igual que con el `.exe`.

## Compilar

Abrir la carpeta en Android Studio y ejecutar el módulo `app`, o desde la terminal:

```bash
./gradlew test assembleDebug
```

Hace falta JDK 17 y el Android SDK 35. Los tests del protocolo no necesitan la plaqueta.

## Licencia

GNU GPLv3, igual que el monitor de Windows del que sale el protocolo. Ver [LICENSE](LICENSE).
