# Berry Med para Android

App de tótem que reemplaza a `berry-monitor.exe`. Lee el monitor PM6750 (la plaqueta Berry) y el termómetro por USB y, mientras dura una consulta, manda las mediciones a la API una vez por segundo, con el mismo JSON que el monitor de Windows.

Adelante, en la tablet, va Google Chrome con la plataforma médica. Berry Med trabaja en segundo plano: arranca sola al encender la tablet y no se muestra. Su pantalla se abre a mano para configurarla o ver el estado.

## Cómo funciona

1. **Al encender la tablet** arranca el servicio de Berry Med, sin abrir ninguna pantalla. Queda en primer plano para Android (con una notificación fija) y sigue andando con la pantalla apagada. También arranca solo después de instalar una versión nueva.
2. **Conecta los equipos USB**: la plaqueta, el termómetro y el hub YKUSH3 (ver [Equipos](#equipos)). Si falta alguno, sigue con los demás.
3. **Escucha las órdenes por Pusher.** Se suscribe al canal público del tótem y espera tres eventos. Pusher no transporta las mediciones.

   | Evento | Qué hace |
   |---|---|
   | `start-monitoring` (configurable) | Limpia lo que quedó de la consulta anterior y empieza a enviar. |
   | `stop-monitoring` (configurable) | Deja de enviar y limpia. |
   | `start-blood-pressure` (fijo) | Le pide al equipo una medición de presión. |

4. **Durante la consulta**, una vez por segundo, a ritmo fijo y sin esperar la respuesta anterior, hace un `POST` a `{URL base}/{tótem}/metrics`. Cada paquete lleva como mucho un segundo de datos (unos 7 KB): el servidor lo reenvía al médico por Pusher, que no acepta mensajes de más de 10 KB. Lleva todo lo que llegó desde el envío anterior: las 7 derivaciones de ECG (unas 250 muestras por segundo cada una), la onda de SpO₂, la de respiración, los latidos y los signos vitales. No manda nada si no hay ningún dato útil. Ante un error de red o un `5xx` reintenta hasta 3 veces; un `4xx` no se reintenta.
   - **Si se perdió el inicio.** El `start-monitoring` sale cuando el profesional entra a la videollamada. Si en ese momento la tablet no estaba escuchando (se conectó tarde, se reinició o se actualizó), mientras espera manda cada 10 segundos un `POST` de prueba con el último segundo de mediciones. Si la plataforma responde `200`, hay una toma de signos abierta y empieza a enviar cada segundo; si responde `404`, sigue esperando. Después de un `stop-monitoring` espera 90 segundos antes de volver a probar, porque la plataforma sigue aceptando paquetes un minuto después del fin.
   - **Si se perdió el fin.** Con la sesión andando, tres respuestas `404` seguidas quieren decir que la toma se cerró, y deja de enviar.
5. **La consulta se corta sola a los 30 minutos si no llega el evento de fin.** Es una red de seguridad para cuando el fin no llega nunca (por ejemplo, se cerró la pestaña o se cayó Pusher): sin ella la tablet seguiría enviando hasta el próximo inicio. Los minutos se cambian en la pantalla; `0` deja la consulta sin tope. Cada inicio limpia todo antes de empezar.
6. **La presión** arranca con `start-blood-pressure` si la plaqueta está conectada y no hay otra medición en curso. Un pedido que no se puede atender no borra la presión que se está mostrando. El equipo informa el estado de la medición dos veces por segundo mientras mide; la medición se da por terminada cuando:
   - el equipo informa un resultado final;
   - deja de informar durante 30 s (pasa cuando termina en error o interferencia); este plazo cuenta desde el primer aviso, porque a veces tarda en empezar a inflar;
   - pasan 3 minutos sin cierre: ahí se manda STOP para no dejar el manguito inflado.
7. **El termómetro** sólo manda una lectura cuando alguien mide. La última lectura reemplaza la temperatura de la plaqueta en el envío. Se borra al empezar y al terminar cada consulta.

## Equipos

La tablet va a un dock USB-C con carga (un solo puerto USB-C no alcanza para todo). Del dock salen la plaqueta, el termómetro y el hub YKUSH3; del hub, un equipo por puerto.

| Equipo | Cómo lo reconoce la app |
|---|---|
| Plaqueta PM6750 | Puerto serie que manda tramas `55 AA` a 115200 baudios. Es un STM32 (fabricante `0483`): un puerto de ST que no contesta sigue siendo la plaqueta, y se vuelve a probar hasta que arranque. |
| Termómetro infrarrojo | Como sólo manda cuando alguien mide, no se lo puede reconocer por lo que transmite: toma el puerto serie que no es la plaqueta y lo deja abierto esperando. Un puerto de ST nunca va al termómetro. |
| Hub YKUSH3 (Yepkit) | HID `04D8:F11B`. Tres puertos USB que se encienden y apagan por comando. En qué puerto va cada equipo se configura en la pantalla (por defecto 1 otoscopio, 2 estetoscopio, 3 auricular). Se cambian a mano desde Testing, y el [Panel Local](#panel-local) enciende el del otoscopio con su botón Encender y los del estetoscopio y el auricular al tocar Escuchar, si estaban apagados. El panel sólo apaga los puertos que encendió él: uno que ya estaba encendido (al arrancar el hub prende los tres) queda encendido al salir, para la teleconsulta. |
| Otoscopio | Cámara UVC `349C:3301` (MJPEG, en cuadrado). Android la lista como cámara externa si el fabricante de la tablet lo habilitó; si no, el Panel Local la abre directo por USB y por eso pide su permiso USB. Trae además un micrófono y un almacenamiento, que no se usan. |
| Adaptador del estetoscopio | Placa de audio USB `3302:29E3` con jack de 3,5 mm; el estetoscopio Dalia StethoAI va al jack. Android la lista como audio USB; el Panel Local la busca por su nombre ("USB Audio" por defecto). |
| Auricular Logitech H390 | Audio USB. Android la lista como auricular USB; el Panel Local lo busca por su nombre ("H390" por defecto, y si no, "Logitech") y su micrófono no se usa. |

El relé `USBRelay2` que cortaba los 12 V de la plaqueta ya no va: el firmware de la plaqueta se corrigió y no se traba.

## Cómo se recupera sola

| Qué pasa | Qué hace la app |
|---|---|
| Se reinicia la tablet o se instala una versión nueva | Arranca el servicio sola. Con "Usar de forma predeterminada" marcado, Android le da el permiso USB a cada equipo sin preguntar. |
| Se perdió el `start-monitoring` o el `stop-monitoring` | Lo detecta por la respuesta de la plataforma al `POST` de prueba (cada 10 s mientras espera) o a los envíos (tres `404` seguidos). Ver el punto 4 de [Cómo funciona](#cómo-funciona). |
| Se corta el wifi | La librería de Pusher reintenta unos 85 s y se rinde. A los 30 s la app le pide otra tanda, y así mientras no haya conexión. Si queda más de 5 min conectando sin respuesta, arma una conexión nueva (cada vez espera el doble, hasta una hora). |
| La plaqueta deja de mandar datos 5 s (colgada, o apagada con el USB conectado) | Borra sus valores (frecuencia, SpO₂, presión, ondas) para no mandarlos congelados como si fueran actuales. La temperatura del termómetro sigue llegando. |
| La plaqueta deja de mandar datos 20 s | Cierra el puerto, lo vuelve a abrir y repite la secuencia de arranque. |
| El puerto de la plaqueta abre pero no transmite, no abre, o no acepta la secuencia de arranque | Lo vuelve a probar cada 5 s hasta que arranque. |
| Se desenchufa el cable de la plaqueta | Espera que vuelva y la toma de nuevo apenas aparece. |
| Un puerto que no es de ST no contesta | No es la plaqueta: queda para el termómetro. |
| Llega un byte cortado o una trama rota | Se descarta sin cortar la lectura ni cerrar la app. |
| El archivo de configuración se corrompe | Vuelve a la configuración vacía y la pantalla avisa, en lugar de cerrar la app. |

## Configuración

En la pantalla de Berry Med, en tres pasos:

- **Destino**: el entorno de OnDoctor, el identificador del tótem (su `external_id`) y la URL base de la API. Elegir el entorno completa la URL base, la clave y el cluster de Pusher; se pueden cambiar a mano antes de guardar:

  | Entorno | URL base | Clave de Pusher | Cluster |
  |---|---|---|---|
  | Producción | `https://portal.ondoctor365.com/api/v1/webhooks/totem` | `b5b07bcf4eb9935b29c7` | `sa1` |
  | Dev | `https://portal-dev.ondoctor365.com/api/v1/webhooks/totem` | `37b143a0f816ccba0eb2` | `sa1` |

  La URL base tiene que empezar con `https://` y no terminar en `/metrics`; la pantalla muestra cómo queda armada. La ruta `medical_devices` del `readme.txt` del exe es de la plataforma anterior. OnDoctor no pide usuario ni contraseña para recibir mediciones, así que el `POST` va sin autenticación.
- **Órdenes**: la clave de Pusher (viene del entorno), el cluster (un desplegable, `us2` o `sa1`; por defecto `sa1`), el canal, los nombres de los eventos de inicio y fin, y los minutos máximos de una consulta (30 por defecto, `0` sin tope). El canal se arma solo como `totem.<external_id>` con el identificador de Destino, y lo sigue mientras nadie lo cambie a mano. Tiene que ser público: un canal `private-` o `presence-` no se conecta y la pantalla lo marca. La clave del exe viejo es de otra plataforma y no recibe las órdenes de OnDoctor.
- **Plaqueta**: la ganancia y el modo de ECG, la ganancia de respiración, el modo de presión y la presión de inflado. La ganancia y el modo se aplican apenas se guardan, sin reconectar. Abajo, en qué puerto del hub YKUSH3 va el otoscopio, el estetoscopio y el auricular; bajo **Estetoscopio**, cómo se llaman para Android el adaptador y el auricular (se leen en Testing → Dispositivos que ve Android; por defecto `USB Audio` y `H390`); y el PIN del [Panel Local](#panel-local) (`1515` si se deja vacío).

Todo queda guardado en la tablet. La pantalla también muestra los signos vitales, si la plaqueta está funcional, el último dato, la conexión con Pusher, el termómetro, el hub YKUSH3 y el resultado del último envío.

## Instalar y dejarla sola

1. **Abrir Berry Med una vez después de instalarla.** Android no le avisa el encendido a una app que nunca se abrió.
2. **Aceptar el permiso USB de la plaqueta, el termómetro, el hub YKUSH3 y el otoscopio marcando "Usar de forma predeterminada".** Android da el permiso por cada conexión: después de un reinicio, cada equipo vuelve como un dispositivo nuevo. Con la casilla marcada lo da solo. Para eso abre una pantalla de Berry Med que no muestra nada y se cierra enseguida, así que Chrome queda adelante. Sin la casilla, cada reinicio muestra un pedido de permiso arriba de Chrome. El adaptador del estetoscopio y el auricular no piden permiso: Android los maneja como audio. El otoscopio sí, por si la tablet no lo lista como cámara (ver [Panel Local](#panel-local)); su pedido aparece la primera vez que se enciende su puerto.

   Cada equipo se pide una sola vez, para no tapar Chrome una y otra vez. Si alguien rechaza o cierra el pedido, la pantalla de Berry Med dice "Falta aceptar el permiso USB" y se resuelve con **Pedir permiso USB**. Un equipo sin permiso no frena a los demás.
3. **Tocar "Evitar que se duerma"** para excluir la app de la optimización de batería. Con Chrome adelante es imprescindible: si Android cierra el servicio, sólo puede volver a levantarlo si la app está excluida. Algunas marcas tienen además su propia lista de apps que se duermen: sacarla de ahí también.
4. **Sin bloqueo con PIN, patrón ni contraseña.** Con bloqueo seguro Android no avisa el encendido hasta que alguien desbloquea, y la app no arranca.
5. **Instalar siempre un APK firmado con la misma clave.** Si cambia la clave hay que desinstalar, y eso borra la configuración.
6. **Probarlo con Chrome adelante**: reiniciar la tablet con todo enchufado y, con una consulta andando, desenchufar y volver a enchufar la plaqueta. En los dos casos la plaqueta tiene que volver sola, sin tocar la pantalla y sin que Chrome quede tapado.

Para ver qué está haciendo, con la tablet conectada a una computadora:

```bash
adb logcat -s BerryMed
```

El servicio corre siempre como `connectedDevice`. `dataSync` no sirve: en Android 15 no puede arrancar al encender la tablet y tiene un tope de 6 horas por día.

## Testing

El botón **Testing** de la pantalla principal abre un panel para comprobar, en la tablet, que cada equipo transmite, antes de que nada se procese y se mande. No abre puertos propios: muestra lo que el servicio ya está leyendo.

- **Iniciar prueba** frena los `POST` de prueba de cada 10 s mientras el panel está abierto. Pusher sigue conectado: si llega un `start-monitoring`, gana la consulta y la prueba se corta sola. Con una consulta enviando o con el [Panel Local](#panel-local) abierto, la prueba no arranca. Al salir de la pantalla la prueba se corta y, si quedó una presión midiendo, se manda STOP.
- **Una tarjeta por medición de la plaqueta.** ECG, SpO₂, respiración y temperatura muestran el valor, las muestras por segundo y un punto verde si llegaron datos en los últimos 3 s. **Probar** mira 5 s de tramas y da OK o el motivo:

  | Medición | OK si en 5 s | Si no |
  |---|---|---|
  | ECG | llegan 200 muestras/s o más y los electrodos están puestos | "No llega la onda", "Llegan N muestras/s", "Electrodos sueltos" |
  | SpO₂ | llegan la onda y los valores, con saturación | "No llega la onda", "Sin dedo en el sensor" |
  | Respiración | llega la onda | "No llega la onda de respiración" |
  | Temperatura | llegan tramas con valor | "La plaqueta no manda temperatura", "Sensor sin conectar" |

  Los umbrales de SpO₂ y respiración son provisorios: alcanza con que llegue la onda.
- **Presión**: **Medir** y **Parar** infla y desinfla de verdad, con los mismos plazos que una medición pedida por Pusher.
- **Termómetro**: la última lectura, hace cuánto llegó y la última línea cruda. Sólo manda cuando alguien aprieta el gatillo.
- **Hub YKUSH3**: **Leer estado** consulta los tres puertos sin cambiarlos; **Encender** y **Apagar** conmutan cada puerto, y la pantalla dice qué equipo lleva cada uno según la configuración. Con una consulta enviando no se cambia ningún puerto. El servicio no toca los puertos solo: al encender la tablet el hub arranca con los tres encendidos, y sólo el [Panel Local](#panel-local) los cambia mientras está abierto.
- **Dispositivos que ve Android**: los dispositivos USB con sus interfaces y permiso, los dispositivos de audio (tipo, nombre, entrada o salida, frecuencia) y las cámaras con su orientación. Sirve para validar el armado antes del Panel Local: si las placas de audio USB conviven y si el otoscopio aparece como cámara externa. Se renueva cada 3 s.
- **Transmisión (no se envía)**: la URL y el JSON que saldría, con su tamaño, y **Copiar JSON**.
- **Consola**: los últimos logs del servicio, los mismos de `adb logcat -s BerryMed`.

## Panel Local

Para atención **presencial, sin teleconsulta**: el médico o el enfermero que está al lado del paciente ve en la tablet todos los instrumentos en vivo, con Chrome cerrado. Es una cosa o la otra: con el panel abierto la tablet no hace teleconsulta.

- **Cómo se entra.** Con el ícono **Panel Local** de la tablet (un segundo ícono de Berry Med, con el mismo dibujo sobre fondo azul, que abre directo el panel) o con el botón **Panel Local** de la pantalla principal. Si la tablet arrancó sin pasar por Berry Med, el panel levanta el servicio.
- **PIN.** Lo pide al abrir y cada vez que el panel vuelve al frente. Por defecto `1515`; se cambia en Configuración, paso Plaqueta. Con una consulta enviando no se abre y lo dice.
- **Qué muestra.** Pantalla apaisada, a pantalla completa, siempre oscura y sin apagarse, pensada para 1280×800 dp (en otras tablets se estira la parte izquierda y los vitales quedan en 312 dp). Nada scrollea. Cada vez que se desbloquea arranca en blanco, sin ondas de antes, y con el ECG a la vista:
  - **Cabecera**: la hora y la fecha, y un chip por equipo (ECG, SpO₂, PA, Temp, Otoscopio, Estetoscopio) con un punto verde si está conectado, ámbar si se está conectando y gris si no está. La plaqueta cuenta como conectada si mandó datos en los últimos 3 s. A la derecha, **Finalizar**.
  - **ECG**: las derivaciones con barrido como un monitor, sobre una grilla de 1 mm y 5 mm medida con la densidad real de la pantalla. Se elige ver la II sola, las tres de los miembros o las siete; la velocidad (12,5, 25 o 50 mm/s) y la ganancia (5, 10 o 20 mm/mV). Arriba dice si la señal es buena, débil o no hay. **Congelar** deja quieto el trazo con la hora en que se congeló (la plaqueta sigue mandando y la FC sigue cambiando) y **Reanudar** vuelve a barrer desde el principio.
  - **Vitales**, siempre en el mismo lugar a la derecha: **FC** (con los electrodos sueltos o el código de arritmia que informa la plaqueta), **SpO₂** con el pulso, **respiración**, **presión** y **temperatura**. La presión muestra el último resultado con la media entre paréntesis y la hora ("10:07 · hace 4 min", "recién"); **Medir** la pide y, mientras mide, se ve la presión del manguito, si está inflando o midiendo, una barra y **Cancelar**. Si terminó sin presión (manguito flojo, demoró, se canceló) dice "Última medición sin resultado". La temperatura es la del termómetro infrarrojo, con la hora de la lectura; desconectado, la tarjeta queda punteada.
  - **Dock** con el otoscopio, el estetoscopio y la cámara de la tablet, siempre los tres. Cada uno dice su estado ("En vivo", "Escuchando", "Lista", "desconectado"); sin el equipo no abre. Tocar uno lo **abre** en lugar del ECG, con una **tira de ECG** de la derivación II y la FC debajo; se vuelve con **← ECG**, **Ver ECG**, el mismo tile o el gesto de atrás. Abrir un dispositivo no lo enciende y volver al ECG no lo apaga: el otoscopio sigue transmitiendo y el estetoscopio sigue sonando.
  - La **cámara** de la tablet: vista previa, frontal o trasera con **Cambiar cámara**, y **Congelar** para mirar una imagen quieta. Pide el permiso de la cámara la primera vez.
  - El **otoscopio**: **Encender** prende su puerto en el YKUSH3, que es enchufarlo y prender su luz, y espera hasta 8 s a que Android lo vea. Si estaba apagado lo enciende; si ya estaba encendido (al arrancar el hub prende los tres) no lo toca. Si no aparece, apaga el puerto si lo encendió el panel y lo dice; si aparece por USB sin permiso, vuelve a pedirlo, deja el puerto encendido y dice que falta aceptar el permiso USB. La imagen va en un círculo, a 800×800 o la más cercana que ofrezca (más grande se come el ancho de banda del USB que comparte con la plaqueta). **Congelar** deja la imagen quieta y **Apagar** suelta la cámara y apaga el puerto si lo encendió el panel. Mientras está encendido, la cámara de la tablet queda en pausa (Android no garantiza dos cámaras abiertas a la vez) y vuelve sola al apagarlo. Hay dos caminos para el video y se eligen solos: si la tablet lista el otoscopio como cámara externa, va **por Camera2**; si no, **por UVC**, directo por USB con su permiso. El encabezado dice cuál usó ("En vivo · UVC"). Como el dispositivo USB aparece un momento antes que la cámara, se esperan 2 s a que aparezca la cámara antes de ir por UVC. Sin YKUSH3 se intenta directo. Al salir del panel el puerto queda como estaba antes de abrirlo: si estaba encendido desde el arranque, sigue encendido y la teleconsulta lo ve.
  - El **estetoscopio**: **Escuchar** enciende en el YKUSH3 los puertos del adaptador y del auricular que estén apagados y, si encendió alguno, espera hasta 5 s a que Android vea las dos placas de audio; si no encendió nada, usa lo que hay. Después empieza a sonar por el auricular H390, con el fonocardiograma en vivo. El sonido pasa por un filtro de 20 a 500 Hz y el **volumen** se cambia en vivo con − y + (2, 5 y 10, que son las ganancias x1, x2 y x4). **Parar** deja de escuchar sin tocar los puertos; al salir del panel se apagan los puertos que encendió el panel. Pide el permiso del micrófono la primera vez. Sin auricular no suena (el parlante de la tablet se acoplaría con el estetoscopio): sólo se ve la onda y el panel lo dice. El adaptador y el auricular se eligen por el nombre configurado, nunca el micrófono ni el parlante por defecto, y el micrófono del auricular nunca hace de estetoscopio aunque su nombre coincida. Sin YKUSH3 se intenta directo. Los filtros Corazón, Pulmón y Amplio y la grabación se ven pero todavía no se pueden usar.
  - **Finalizar** pregunta antes de cerrar; **Finalizar sesión** cierra el panel (como hacía Salir) y **Cancelar**, tocar afuera o el gesto de atrás vuelven al panel.
- **Qué no hace.** No identifica al paciente, no guarda nada (ni las imágenes congeladas ni el sonido: lo que se capture o grabe en el panel vive sólo mientras está abierto y se descarta al finalizar o al bloquearse), no envía nada a OnDoctor y no tiene PDF. **No tiene alarmas**: ni límites de FC ni colores de alarma, el color de cada número es fijo. El termómetro infrarrojo no tiene botones: manda una lectura cuando alguien aprieta el gatillo, y la tarjeta sólo dice si está conectado y la última lectura. Mientras está abierto no sale ningún `POST`, ni siquiera el de prueba de cada 10 s, y se ignoran `start-monitoring`, `stop-monitoring` y `start-blood-pressure` (quedan en la consola). Si estaba abierto Testing, la prueba se corta; con el panel abierto, Testing no deja iniciar otra, porque el panel está atendiendo a un paciente. Al salir (con **Finalizar**, el gesto de atrás desde el ECG o cambiando de app) todo vuelve a la normalidad y, si quedó una presión midiendo, se manda STOP.
- **La escala del ECG es la del papel, en milímetros reales.** 25 mm/s son 25 mm de pantalla por segundo, no una aproximación en dp. La escala vertical, en cambio, es aproximada: la plaqueta manda cada muestra como un byte de 0 a 255 con la línea de base en 128 y no documenta cuántos milivoltios son. El papel supone 40 unidades por mV; la ganancia sirve para ajustarlo a ojo.
- **Tipografía e íconos**: Barlow y Barlow Semi Condensed (licencia OFL, en `app/src/main/res/font/`, con sus licencias en `app/src/main/assets/fonts/`) e íconos de Material Symbols Rounded (Apache 2.0).

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
