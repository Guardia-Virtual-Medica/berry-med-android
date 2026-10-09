# Berry Med para Android

App de tótem que reemplaza a `berry-monitor.exe`. Lee el monitor PM6750 (la plaqueta Berry) y el termómetro por USB y, mientras dura una consulta, manda las mediciones a la API una vez por segundo, con el mismo JSON que el monitor de Windows.

Adelante, en la tablet, va Google Chrome con la plataforma médica. Berry Med trabaja en segundo plano: arranca sola al encender la tablet y no se muestra. Su pantalla se abre a mano para configurarla o ver el estado.

## Cómo funciona

1. **Al encender la tablet** arranca el servicio de Berry Med, sin abrir ninguna pantalla. Queda en primer plano para Android (con una notificación fija) y sigue andando con la pantalla apagada. También arranca solo después de instalar una versión nueva.
2. **Conecta los equipos USB**: la plaqueta, el termómetro y el relé (ver [Equipos](#equipos)). Si falta alguno, sigue con los demás.
3. **Escucha las órdenes por Pusher.** Se suscribe al canal público del tótem y espera tres eventos. Pusher no transporta las mediciones.

   | Evento | Qué hace |
   |---|---|
   | `start-monitoring` (configurable) | Limpia lo que quedó de la consulta anterior y empieza a enviar. |
   | `stop-monitoring` (configurable) | Deja de enviar y limpia. |
   | `start-blood-pressure` (fijo) | Le pide al equipo una medición de presión. |

4. **Durante la consulta**, una vez por segundo hace un `POST` a `{URL base}/{tótem}/metrics` con autenticación básica. Lleva todo lo que llegó desde el envío anterior: las 7 derivaciones de ECG (unas 250 muestras por segundo cada una), la onda de SpO₂, la de respiración, los latidos y los signos vitales. No manda nada si no hay ningún dato útil. Si la API no contesta `200`, reintenta hasta 3 veces y pasa al envío siguiente.
5. **La consulta se corta sola a los 30 minutos si no llega el evento de fin.** Es una red de seguridad para cuando el fin no llega nunca (por ejemplo, se cerró la pestaña o se cayó Pusher): sin ella la tablet seguiría enviando hasta el próximo inicio. Los minutos se cambian en la pantalla; `0` deja la consulta sin tope. Cada inicio limpia todo antes de empezar.
6. **La presión** arranca con `start-blood-pressure` si la plaqueta está conectada y no hay otra medición en curso. Un pedido que no se puede atender no borra la presión que se está mostrando. El equipo informa el estado de la medición dos veces por segundo mientras mide; la medición se da por terminada cuando:
   - el equipo informa un resultado final;
   - deja de informar durante 30 s (pasa cuando termina en error o interferencia); este plazo cuenta desde el primer aviso, porque a veces tarda en empezar a inflar;
   - pasan 3 minutos sin cierre: ahí se manda STOP para no dejar el manguito inflado.
7. **El termómetro** sólo manda una lectura cuando alguien mide. La última lectura reemplaza la temperatura de la plaqueta en el envío. Se borra al empezar y al terminar cada consulta.

## Equipos

Van los tres a un hub USB con alimentación.

| Equipo | Cómo lo reconoce la app |
|---|---|
| Plaqueta PM6750 | Puerto serie que manda tramas `55 AA` a 115200 baudios. Es un STM32 (fabricante `0483`): un puerto de ST que no contesta se trata como plaqueta trabada. |
| Termómetro infrarrojo | Como sólo manda cuando alguien mide, no se lo puede reconocer por lo que transmite: toma el puerto serie que no es la plaqueta y lo deja abierto esperando. Un puerto de ST nunca va al termómetro. |
| Relé `USBRelay2` | HID `16C0:05DF`. Su contacto corta los 12 V de la plaqueta para reiniciarla cuando se traba. |

## Cómo se recupera sola

| Qué pasa | Qué hace la app |
|---|---|
| Se reinicia la tablet o se instala una versión nueva | Arranca el servicio sola. Con "Usar de forma predeterminada" marcado, Android le da el permiso USB a cada equipo sin preguntar. |
| Se corta el wifi | La librería de Pusher reintenta unos 85 s y se rinde. A los 30 s la app le pide otra tanda, y así mientras no haya conexión. Si queda más de 5 min conectando sin respuesta, arma una conexión nueva (cada vez espera el doble, hasta una hora). |
| La plaqueta deja de mandar datos 20 s | Corta los 12 V durante 5 s con el relé y vuelve a conectar. |
| El puerto de la plaqueta abre pero no transmite, no abre, o no acepta la secuencia de arranque tres veces seguidas | Igual: corte de 5 s con el relé. |
| La plaqueta no aparece en el USB durante 3 min | Corta igual (puede tener alimentación y no llegar a enumerar), y mientras siga sin aparecer, como mucho cada 30 min. Con la plaqueta apagada a propósito y el relé enchufado, el relé conmuta cada media hora. |
| Un corte no devolvió los 12 V (falló el relé o la app se cerró en medio) | Mientras la plaqueta no aparezca, le pide al relé una vez por minuto que deje pasar la alimentación. |
| Se desenchufa el cable de la plaqueta | No corta enseguida: espera que vuelva. Si no vuelve en 3 min, aplica la regla de arriba. |
| Un puerto que no es de ST no contesta | No toca el relé nunca: queda para el termómetro. |
| Llega un byte cortado o una trama rota | Se descarta sin cortar la lectura ni cerrar la app. |
| El archivo de configuración se corrompe | Vuelve a la configuración vacía y la pantalla avisa, en lugar de cerrar la app. |

Entre un corte del relé y el siguiente pasa como mínimo un minuto, para no cortar la plaqueta mientras arranca. Cada orden al relé se confirma leyendo su estado. El relé recibe 8 bytes `[comando, relé, 0…]`, que es lo que le llega desde el exe cuando éste manda 9 con el report ID 0.

## Configuración

En la pantalla de Berry Med, en tres pasos:

- **Destino**: el entorno de OnDoctor, el identificador del tótem (su `external_id`) y la URL base de la API. Elegir el entorno completa la URL base, la clave y el cluster de Pusher; se pueden cambiar a mano antes de guardar:

  | Entorno | URL base | Clave de Pusher | Cluster |
  |---|---|---|---|
  | Producción | `https://portal.ondoctor365.com/api/v1/webhooks/totem` | `b5b07bcf4eb9935b29c7` | `sa1` |
  | Dev | `https://portal-dev.ondoctor365.com/api/v1/webhooks/totem` | `adac7a549b509b95c930` | `sa1` |

  La URL base tiene que empezar con `https://` y no terminar en `/metrics`; la pantalla muestra cómo queda armada. La ruta `medical_devices` del `readme.txt` del exe es de la plataforma anterior. OnDoctor no pide usuario ni contraseña para recibir mediciones, así que el `POST` va sin autenticación.
- **Órdenes**: la clave de Pusher (viene del entorno), el cluster (un desplegable, `us2` o `sa1`; por defecto `sa1`), el canal, los nombres de los eventos de inicio y fin, y los minutos máximos de una consulta (30 por defecto, `0` sin tope). El canal se arma solo como `totem.<external_id>` con el identificador de Destino, y lo sigue mientras nadie lo cambie a mano. Tiene que ser público: un canal `private-` o `presence-` no se conecta y la pantalla lo marca. La clave del exe viejo es de otra plataforma y no recibe las órdenes de OnDoctor.
- **Plaqueta**: la ganancia y el modo de ECG, la ganancia de respiración, el modo de presión y la presión de inflado. La ganancia y el modo se aplican apenas se guardan, sin reconectar.

Todo queda guardado en la tablet. La pantalla también muestra los signos vitales, si la plaqueta está funcional, el último dato, la conexión con Pusher, el termómetro, el relé y el resultado del último envío.

## Instalar y dejarla sola

1. **Abrir Berry Med una vez después de instalarla.** Android no le avisa el encendido a una app que nunca se abrió.
2. **Aceptar el permiso USB de la plaqueta, el termómetro y el relé marcando "Usar de forma predeterminada".** Android da el permiso por cada conexión: después de un reinicio o de un corte del relé, la plaqueta vuelve como un dispositivo nuevo. Con la casilla marcada lo da solo. Para eso abre una pantalla de Berry Med que no muestra nada y se cierra enseguida, así que Chrome queda adelante. Sin la casilla, cada reinicio o corte del relé muestra un pedido de permiso arriba de Chrome.

   Cada equipo se pide una sola vez, para no tapar Chrome una y otra vez. Si alguien rechaza o cierra el pedido, la pantalla de Berry Med dice "Falta aceptar el permiso USB" y se resuelve con **Pedir permiso USB**. Un equipo sin permiso no frena a los demás.
3. **Tocar "Evitar que se duerma"** para excluir la app de la optimización de batería. Con Chrome adelante es imprescindible: si Android cierra el servicio, sólo puede volver a levantarlo si la app está excluida. Algunas marcas tienen además su propia lista de apps que se duermen: sacarla de ahí también.
4. **Sin bloqueo con PIN, patrón ni contraseña.** Con bloqueo seguro Android no avisa el encendido hasta que alguien desbloquea, y la app no arranca.
5. **Instalar siempre un APK firmado con la misma clave.** Si cambia la clave hay que desinstalar, y eso borra la configuración.
6. **Probarlo con Chrome adelante**: reiniciar la tablet con todo enchufado y, con una consulta andando, forzar un corte del relé apagando la plaqueta. En los dos casos la plaqueta tiene que volver sola, sin tocar la pantalla y sin que Chrome quede tapado.

Para ver qué está haciendo, con la tablet conectada a una computadora:

```bash
adb logcat -s BerryMed
```

El servicio corre siempre como `connectedDevice`. `dataSync` no sirve: en Android 15 no puede arrancar al encender la tablet y tiene un tope de 6 horas por día.

## Qué no hace esta versión

No publica `/health` y no usa Bluetooth. La aplicación web no cambia: cuando el `POST` llega, sigue igual que con el exe. Ojo con el volumen: el exe por USB pierde una de cada dos tramas, así que esta app manda el doble de muestras de ECG que un tótem con Windows.

## Compilar

Abrir la carpeta en Android Studio y ejecutar el módulo `app`, o desde la terminal:

```bash
./gradlew test assembleDebug
```

Hace falta JDK 17 y el Android SDK 35. Los tests no necesitan la plaqueta.

## Licencia

GNU GPLv3, igual que el monitor de Windows del que sale el protocolo. Ver [LICENSE](LICENSE).
