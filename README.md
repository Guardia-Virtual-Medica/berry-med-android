# Berry Med para Android

App de tótem que reemplaza a `berry-monitor.exe`. Con la plaqueta Berry conectada por USB, lee las tramas del PM6750, las interpreta y, mientras la sesión está activa, hace un `POST` por segundo a `{API_URL}/{TOTEM_ID}/metrics` con el mismo JSON que el monitor de Windows.

Pusher no transporta las mediciones. La tablet se suscribe al canal público y espera `start-monitoring`, `stop-monitoring` y `start-blood-pressure`.

## Qué necesita la tablet

- Android 8 o superior, con USB en modo host (OTG).
- Un hub USB con alimentación. La plaqueta, el termómetro infrarrojo y el relé `USBRelay2` son tres dispositivos distintos.
- La plaqueta se reconoce porque manda tramas `55 AA` a 115200 baudios. El termómetro, porque manda líneas JSON con `valor_temperatura_ir`. El relé es el HID `16C0:05DF`.

Si el puerto abre y no llegan tramas, o si la plaqueta se queda muda 20 segundos, la app corta los 12 V durante 5 segundos con el relé y vuelve a conectar. Si se desenchufa el cable, no toca el relé.

## Configuración

En la pantalla van la URL base, el tótem, el usuario y la contraseña, la clave y el cluster de Pusher, el canal público y los nombres de los eventos de inicio y fin. También la ganancia y el modo de ECG, la ganancia de respiración y el modo de presión. Se guardan en el dispositivo.

La sesión se corta sola a los 30 minutos si no llega el evento de fin. `0` desactiva ese tope.

Conviene aceptar el permiso USB la primera vez y, en la misma pantalla, excluir la app de la optimización de batería. El servicio queda en primer plano, sigue con la pantalla apagada y vuelve a arrancar al encender la tablet.

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
