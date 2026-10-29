# Berry Med para Android

App de tótem que reemplaza a `berry-monitor.exe`. Lee el monitor PM6750 (la plaqueta Berry) y el termómetro por USB y, mientras dura una consulta, manda las mediciones a la API una vez por segundo, con el mismo JSON que el monitor de Windows.

La documentación del proyecto está en [`docs/`](docs/): los planes de cada etapa (`docs/planes/`), las decisiones de producto con su fecha ([`docs/decisiones.md`](docs/decisiones.md)), el armado del tótem ([`docs/hardware.md`](docs/hardware.md)), el handoff de diseño del Panel Local (`docs/redisenio/`) y las revisiones (`docs/revisiones/`).

Adelante, en la tablet, va el kiosco de la plataforma médica, **dentro de Berry Med** (ver [Kiosco](#kiosco)): Berry Med es la app de inicio de la tablet y lo muestra a pantalla completa. Detrás, el servicio de Berry Med lee los equipos y arranca solo al encender la tablet. La configuración se abre desde el kiosco con un gesto oculto y el PIN.

## Cómo funciona

1. **Al encender la tablet** arranca el servicio de Berry Med, sin abrir ninguna pantalla. Queda en primer plano para Android (con una notificación fija) y sigue andando con la pantalla apagada. También arranca solo después de instalar una versión nueva.
2. **Conecta los equipos USB**: la plaqueta, el termómetro y el hub YKUSH3 (ver [Equipos](#equipos)). Si falta alguno, sigue con los demás.
3. **Escucha las órdenes por Pusher.** Se suscribe al canal público del tótem y espera estos eventos. Pusher no transporta las mediciones.

   | Evento | Qué hace |
   |---|---|
   | `start-monitoring` (configurable) | Limpia lo que quedó de la consulta anterior y empieza a enviar. |
   | `stop-monitoring` (configurable) | Deja de enviar y limpia. |
   | `start-blood-pressure` (fijo) | Le pide al equipo una medición de presión. |
   | `start_oth`, `stop_oth`, `start_steth`, `stop_steth` (fijos) | Enciende o apaga el otoscopio o el estetoscopio en la teleconsulta (ver [Teleconsulta](#teleconsulta-otoscopio-y-estetoscopio)). No dependen de que haya una consulta de signos enviando. |

4. **Durante la consulta**, una vez por segundo, a ritmo fijo y sin esperar la respuesta anterior, hace un `POST` a `{URL base}/{tótem}/metrics`. Cada paquete lleva como mucho un segundo de datos (unos 7 KB): el servidor lo reenvía al médico por Pusher, que no acepta mensajes de más de 10 KB. Lleva todo lo que llegó desde el envío anterior: las 7 derivaciones de ECG (unas 250 muestras por segundo cada una), la onda de SpO₂, la de respiración, los latidos y los signos vitales. No manda nada si no hay ningún dato útil; una lectura del termómetro sola cuenta como dato útil. Cualquier `2xx` es un envío correcto. Ante un error de red o un `5xx` espera medio segundo y reintenta una vez; un `4xx` no se reintenta. Cada intento tiene 5 s en total, así un paquete no tarda más de unos 11 s: uno que llega tarde ya no le sirve al médico, que mira en vivo. Si la red se traba y se juntan más de 10 paquetes, se tiran los más viejos; la consola lo registra ("Se descartó un paquete") y el último envío dice cuántos se descartaron en la consulta.
   - **Si se perdió el inicio.** El `start-monitoring` sale cuando el profesional entra a la videollamada. Si en ese momento la tablet no estaba escuchando (se conectó tarde, se reinició o se actualizó), mientras espera manda cada 10 segundos un `POST` de prueba con el último segundo de mediciones. Si la plataforma responde `200`, hay una toma de signos abierta y empieza a enviar cada segundo; si responde `404`, sigue esperando. Después de un `stop-monitoring` espera 90 segundos antes de volver a probar, porque la plataforma sigue aceptando paquetes un minuto después del fin.
   - **Si se perdió el fin.** Con la sesión andando, tres respuestas `404` seguidas quieren decir que la toma se cerró, y deja de enviar. No cuentan los `404` de los primeros 5 s de la consulta (la plataforma puede tardar un momento en abrir la toma) ni los que llegan tarde de una consulta anterior.
   - **No hay tope de tiempo.** La consulta termina sólo con el `stop-monitoring` o con esos tres `404`. Cada inicio limpia todo antes de empezar.
5. **La presión** arranca con `start-blood-pressure` si la plaqueta está conectada y no hay otra medición en curso. Un pedido que no se puede atender no borra la presión que se está mostrando. El equipo informa el estado de la medición dos veces por segundo mientras mide; la medición se da por terminada cuando:
   - el equipo informa un resultado final;
   - deja de informar durante 30 s (pasa cuando termina en error o interferencia); este plazo cuenta desde el primer aviso, porque a veces tarda en empezar a inflar;
   - pasan 3 minutos sin cierre: ahí se manda STOP para no dejar el manguito inflado.
6. **El termómetro** sólo manda una lectura cuando alguien mide. La última lectura reemplaza la temperatura de la plaqueta en el envío, y se manda aunque la plaqueta esté desenchufada o muda. Se borra al empezar y al terminar cada consulta.

## Equipos

La tablet va a un dock USB-C con carga (un solo puerto USB-C no alcanza para todo). Del dock salen la plaqueta, el termómetro y el hub YKUSH3; del hub, un equipo por puerto.

| Equipo | Cómo lo reconoce la app |
|---|---|
| Plaqueta PM6750 | Puerto serie que manda tramas `55 AA` a 115200 baudios. Es un STM32 (fabricante `0483`): un puerto de ST que no contesta sigue siendo la plaqueta, y se vuelve a probar hasta que arranque. |
| Termómetro infrarrojo | Como sólo manda cuando alguien mide, no se lo puede reconocer por lo que transmite: toma el puerto serie que no es la plaqueta y lo deja abierto esperando. Un puerto de ST nunca va al termómetro. |
| Hub YKUSH3 (Yepkit) | HID `04D8:F11B`. Tres puertos USB que se encienden y apagan por comando. En qué puerto va cada equipo se configura en la pantalla (por defecto 1 otoscopio, 2 estetoscopio, 3 auricular). Se cambian a mano desde Testing, y el [Panel Local](#panel-local) enciende el del otoscopio con su botón Encender y los del estetoscopio y el auricular al tocar Escuchar, si estaban apagados. El panel sólo apaga los puertos que encendió él: uno que ya estaba encendido (al arrancar el hub prende los tres) queda encendido al salir, para la teleconsulta. Si el hub no contestó la lectura del estado, el panel enciende el puerto igual pero no lo apaga al salir: prefiere dejarlo prendido de más que dejar a la teleconsulta sin el equipo. Cada equipo va en un puerto distinto; la pantalla no deja guardar dos en el mismo. |
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
| Se desenchufa el cable de la plaqueta | Espera que vuelva y la toma de nuevo apenas aparece. Una presión que estaba midiendo se pierde (la plaqueta arranca de cero): queda "sin resultado" y se puede pedir otra apenas vuelve. |
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
- **Órdenes**: la clave de Pusher (viene del entorno), el cluster (un desplegable, `us2` o `sa1`; por defecto `sa1`), el canal y los nombres de los eventos de inicio y fin. El canal se arma solo como `totem.<external_id>` con el identificador de Destino, y lo sigue mientras nadie lo cambie a mano. Tiene que ser público: un canal `private-` o `presence-` no se conecta y la pantalla lo marca. La clave del exe viejo es de otra plataforma y no recibe las órdenes de OnDoctor.
- **Plaqueta**: la ganancia y el modo de ECG, la ganancia de respiración, el modo de presión y la presión de inflado. La ganancia y el modo se aplican apenas se guardan, sin reconectar. Abajo, en qué puerto del hub YKUSH3 va el otoscopio, el estetoscopio y el auricular (uno distinto para cada uno); bajo **Estetoscopio**, cómo se llaman para Android el adaptador y el auricular (se leen en Testing → Dispositivos que ve Android; por defecto `USB Audio` y `H390`); y el PIN del [Panel Local](#panel-local) (`1515` si se deja vacío).

Todo queda guardado en la tablet. La pantalla también muestra los signos vitales, si la plaqueta está funcional, el último dato, la conexión con Pusher, el termómetro, el hub YKUSH3 y el resultado del último envío.

## Instalar y dejarla sola

1. **Abrir Berry Med una vez después de instalarla.** Android no le avisa el encendido a una app que nunca se abrió.
2. **Aceptar el permiso USB de la plaqueta, el termómetro, el hub YKUSH3 y el otoscopio marcando "Usar de forma predeterminada".** Android da el permiso por cada conexión: después de un reinicio, cada equipo vuelve como un dispositivo nuevo. Con la casilla marcada lo da solo. Para eso abre una pantalla de Berry Med que no muestra nada y se cierra enseguida, así que el kiosco queda adelante. Sin la casilla, cada reinicio muestra un pedido de permiso arriba del kiosco. El adaptador del estetoscopio y el auricular no piden permiso: Android los maneja como audio. El otoscopio sí, por si la tablet no lo lista como cámara (ver [Panel Local](#panel-local)); su pedido aparece la primera vez que se enciende su puerto.

   Conviene abrir el Panel Local una vez y aceptar el permiso de la cámara **antes** de aceptar el permiso USB del otoscopio: en Android 10 o más, Android puede negar sin diálogo el permiso USB de un dispositivo de video si la app todavía no tiene el de cámara.

   Cada equipo se pide una sola vez, para no tapar el kiosco una y otra vez. Si alguien rechaza o cierra el pedido, la pantalla de Berry Med dice "Falta aceptar el permiso USB" y se resuelve con **Pedir permiso USB**. Un equipo sin permiso no frena a los demás.
3. **Cargar la URL del kiosco** (Configuración → Destino, la misma que abría Chrome, con el slug de la organización), **aceptar los permisos de cámara y micrófono** (los usan la videollamada del kiosco y la [teleconsulta](#teleconsulta-otoscopio-y-estetoscopio)) y **elegir Berry Med como app de inicio**: tocar el botón de inicio de la tablet y elegir Berry Med con "Siempre" (o Ajustes → Apps → Apps predeterminadas → App de inicio). Así Android la abre al encender y el botón de inicio vuelve al kiosco.
4. **Tocar "Evitar que se duerma"** para excluir la app de la optimización de batería. Es imprescindible: si Android cierra el servicio, sólo puede volver a levantarlo si la app está excluida. Algunas marcas tienen además su propia lista de apps que se duermen: sacarla de ahí también.
5. **Sin bloqueo con PIN, patrón ni contraseña.** Con bloqueo seguro Android no avisa el encendido hasta que alguien desbloquea, y la app no arranca.
6. **Instalar siempre un APK firmado con la misma clave.** Si cambia la clave hay que desinstalar, y eso borra la configuración.
7. **Probarlo con el kiosco adelante**: reiniciar la tablet con todo enchufado (tiene que abrir el kiosco solo) y, con una consulta andando, desenchufar y volver a enchufar la plaqueta. En los dos casos la plaqueta tiene que volver sola, sin tocar la pantalla y sin que el kiosco quede tapado.

Para ver qué está haciendo, con la tablet conectada a una computadora:

```bash
adb logcat -s BerryMed
```

El servicio corre siempre como `connectedDevice`. `dataSync` no sirve: en Android 15 no puede arrancar al encender la tablet y tiene un tope de 6 horas por día. Cuando una pantalla de Berry Med está adelante (el kiosco, la principal, Testing o el Panel Local) y los permisos están otorgados, pasa a `connectedDevice|camera|microphone` y ya no baja; Android no deja sumar esos tipos desde segundo plano ni al encender la tablet. Como el kiosco es la app de inicio, eso pasa solo apenas arranca.

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
- **Teleconsulta (LiveKit)**: si el servicio tiene cámara y micrófono, el estado de la sala (desconectada, conectando, conectada, error) con su nombre y a qué hora se vio el token y a qué hora vence para entrar (el token nunca se muestra), qué pista publica, los cuadros por segundo del otoscopio, el nivel del estetoscopio, si Android silencia la captura y el último error. Abajo, una **prueba manual** con **Servidor** y **Token** y los botones **Publicar otoscopio**, **Publicar estetoscopio** y **Cortar**: hace lo mismo que el token del kiosco, para probar con un servidor LiveKit propio y un token de `lk token create`. Sigue con Testing cerrado hasta **Cortar**, hasta que se abra el Panel Local o hasta que el kiosco muestre un token; mientras tanto, las órdenes de Pusher para los instrumentos se ignoran.
- **Transmisión (no se envía)**: la URL y el JSON que saldría, con su tamaño, y **Copiar JSON**.
- **Consola**: los últimos logs del servicio, los mismos de `adb logcat -s BerryMed`.

## Panel Local

Para atención **presencial, sin teleconsulta**: el médico o el enfermero que está al lado del paciente ve en la tablet todos los instrumentos en vivo, sin el kiosco adelante. Es una cosa o la otra: con el panel abierto la tablet no hace teleconsulta.

- **Cómo se entra.** Con el ícono **Panel Local** de la tablet (un segundo ícono de Berry Med, con el mismo dibujo sobre fondo azul, que abre directo el panel) o con el botón **Panel Local** de la pantalla principal. Si la tablet arrancó sin pasar por Berry Med, el panel levanta el servicio.
- **PIN.** Lo pide al abrir y cada vez que el panel vuelve al frente. Por defecto `1515`; se cambia en Configuración, paso Plaqueta. Con una consulta enviando no se abre y lo dice.
- **Qué muestra.** Pantalla apaisada, a pantalla completa, siempre oscura y sin apagarse, pensada para 1280×800 dp (en otras tablets se estira la parte izquierda y los vitales quedan en 312 dp). Nada scrollea. Cada vez que se desbloquea arranca en blanco, sin ondas de antes, y con el ECG a la vista:
  - **Cabecera**: la hora y la fecha, y un chip por equipo (ECG, SpO₂, PA, Temp, Otoscopio, Estetoscopio) con un punto verde si está conectado, ámbar si se está conectando y gris si no está. La plaqueta cuenta como conectada si mandó datos en los últimos 3 s. A la derecha, **Finalizar**.
  - **ECG**: las derivaciones con barrido como un monitor, sobre una grilla de 1 mm y 5 mm medida con la densidad real de la pantalla. Se elige ver la II sola, las tres de los miembros o las siete; la velocidad (12,5, 25 o 50 mm/s) y la ganancia (5, 10 o 20 mm/mV). Arriba dice si la señal es buena, débil o no hay. **Congelar** deja quieto el trazo con la hora en que se congeló (la plaqueta sigue mandando y la FC sigue cambiando) y **Reanudar** vuelve a barrer desde el principio.
  - **Vitales**, siempre en el mismo lugar a la derecha: **FC** (con los electrodos sueltos o el código de arritmia que informa la plaqueta), **SpO₂** con el pulso, **respiración**, **presión** y **temperatura**. La presión muestra el último resultado con la media entre paréntesis y la hora ("10:07 · hace 4 min", "recién"); **Medir** la pide y, mientras mide, se ve la presión del manguito, si está inflando o midiendo, una barra y **Cancelar**. Si terminó sin presión (manguito flojo, demoró, se canceló) dice "Última medición sin resultado". La temperatura es la del termómetro infrarrojo, con la hora de la lectura; desconectado, la tarjeta queda punteada.
  - **Dock** con el otoscopio, el estetoscopio y la cámara de la tablet, siempre los tres. Cada uno dice su estado ("En vivo", "Escuchando", "Lista", "desconectado"); sin el equipo no abre. Tocar uno lo **abre** en lugar del ECG, con una **tira de ECG** de la derivación II y la FC debajo; se vuelve con **← ECG**, **Ver ECG**, el mismo tile o el gesto de atrás. Abrir un dispositivo no lo enciende y volver al ECG no lo apaga: el otoscopio sigue transmitiendo y el estetoscopio sigue sonando.
  - La **cámara** de la tablet: vista previa, frontal o trasera con **Cambiar cámara**, y **Congelar** para mirar una imagen quieta. Pide el permiso de la cámara la primera vez.
  - **Capturas** del otoscopio y de la cámara, en dos pasos: **Congelar** deja la imagen quieta ("CONGELADO · sin guardar") y ahí **Guardar captura** la agrega a la lista de ese dispositivo con la hora y vuelve al vivo, o **Descartar y volver** sigue en vivo sin guardarla. Abajo se ven las últimas cuatro en miniatura con la cuenta total; tocar una la muestra en el visor hasta **Volver al vivo**. Viven sólo en memoria (las anteriores a la última quedan como miniatura) y se descartan al finalizar o al bloquearse el panel; cambiar de dispositivo no las borra.
  - El **otoscopio**: **Encender** prende su puerto en el YKUSH3, que es enchufarlo y prender su luz, y espera hasta 8 s a que Android lo vea. Si estaba apagado lo enciende; si ya estaba encendido (al arrancar el hub prende los tres) no lo toca. Si no aparece, apaga el puerto si lo encendió el panel y lo dice; si aparece por USB sin permiso, vuelve a pedirlo, deja el puerto encendido y dice que falta aceptar el permiso USB. La imagen va en un círculo, a 800×800 o la más cercana que ofrezca (más grande se come el ancho de banda del USB que comparte con la plaqueta). **Congelar** deja la imagen quieta para guardarla o descartarla (ver Capturas) y **Apagar** suelta la cámara y apaga el puerto si lo encendió el panel. Mientras está encendido, la cámara de la tablet queda en pausa (Android no garantiza dos cámaras abiertas a la vez) y vuelve sola al apagarlo. Hay dos caminos para el video y se eligen solos: si la tablet lista el otoscopio como cámara externa, va **por Camera2**; si no, **por UVC**, directo por USB con su permiso. El encabezado dice cuál usó ("En vivo · UVC"). Como el dispositivo USB aparece un momento antes que la cámara, se esperan 2 s a que aparezca la cámara antes de ir por UVC. Sin YKUSH3 se intenta directo. Al salir del panel el puerto queda como estaba antes de abrirlo: si estaba encendido desde el arranque, sigue encendido y la teleconsulta lo ve.
  - El **estetoscopio**: **Escuchar** enciende en el YKUSH3 los puertos del adaptador y del auricular que estén apagados y, si encendió alguno, espera hasta 5 s a que Android vea las dos placas de audio; si no encendió nada, usa lo que hay. Después empieza a sonar por el auricular H390, con el fonocardiograma en vivo. El **filtro** se cambia en vivo, sin cortar la escucha: **Corazón** (20 a 250 Hz, el de arranque), **Pulmón** (100 a 1000 Hz, para que los latidos no tapen los ruidos respiratorios) y **Amplio** (20 a 1000 Hz); el visor dice cuál está puesto. El **volumen** va de 0 a 10 con − y + (5 deja la señal como llega, 10 la duplica y 0 es silencio; arranca en 6). Mientras escucha, el botón principal es **Grabar 15 s**: graba lo que suena, ya filtrado y con el volumen, con la cuenta regresiva en el botón y en el dock ("Grabando · 12 s"), y al terminar la agrega a la lista con su filtro y su hora; tocarlo mientras graba corta y guarda lo que lleva. Mientras graba, el filtro no se cambia (la grabación lleva su nombre). Debajo del fonocardiograma se ven las últimas tres grabaciones: tocar una la **reproduce por el auricular** (si se estaba escuchando, primero para la escucha) y tocarla otra vez la para; sin auricular no se reproduce y lo dice. Las grabaciones viven sólo en memoria y se descartan al finalizar o al bloquearse el panel. **Parar** deja de escuchar sin tocar los puertos (si estaba grabando, guarda lo que lleva si pasó de 1 s); al salir del panel se apagan los puertos que encendió el panel. Pide el permiso del micrófono la primera vez. Sin auricular no suena (el parlante de la tablet se acoplaría con el estetoscopio): sólo se ve la onda y el panel lo dice. El adaptador y el auricular se eligen por el nombre configurado, nunca el micrófono ni el parlante por defecto, y el micrófono del auricular nunca hace de estetoscopio aunque su nombre coincida. Sin YKUSH3 se intenta directo.
  - **Finalizar** pregunta antes de cerrar; **Finalizar sesión** cierra el panel (como hacía Salir) y **Cancelar**, tocar afuera o el gesto de atrás vuelven al panel.
- **Qué no hace.** No identifica al paciente, no guarda nada (ni las imágenes congeladas ni el sonido: lo que se capture o grabe en el panel vive sólo mientras está abierto y se descarta al finalizar o al bloquearse), no envía nada a OnDoctor y no tiene PDF. **No tiene alarmas**: ni límites de FC ni colores de alarma, el color de cada número es fijo. El termómetro infrarrojo no tiene botones: manda una lectura cuando alguien aprieta el gatillo, y la tarjeta sólo dice si está conectado y la última lectura. Mientras está abierto no sale ningún `POST`, ni siquiera el de prueba de cada 10 s, y se ignoran `start-monitoring`, `stop-monitoring` y `start-blood-pressure` (quedan en la consola). Si estaba abierto Testing, la prueba se corta; con el panel abierto, Testing no deja iniciar otra, porque el panel está atendiendo a un paciente. Al salir (con **Finalizar**, el gesto de atrás desde el ECG o cambiando de app) todo vuelve a la normalidad y, si quedó una presión midiendo, se manda STOP.
- **La escala del ECG es la del papel, en milímetros reales.** 25 mm/s son 25 mm de pantalla por segundo, no una aproximación en dp. La escala vertical, en cambio, es aproximada: la plaqueta manda cada muestra como un byte de 0 a 255 con la línea de base en 128 y no documenta cuántos milivoltios son. El papel supone 40 unidades por mV; la ganancia sirve para ajustarlo a ojo.
- **Tipografía e íconos**: Barlow y Barlow Semi Condensed (licencia OFL, en `app/src/main/res/font/`, con sus licencias en `app/src/main/assets/fonts/`) e íconos de Material Symbols Rounded (Apache 2.0).

## Kiosco

El kiosco de OnDoctor corre dentro de Berry Med, como lo hacía la app del proveedor, y no en Chrome: la pantalla **Kiosco** es un `WebView` a pantalla completa, horizontal y sin barras, con la **URL del kiosco** de la configuración.

- **Es la app de inicio** de la tablet: Android la abre al encender y el botón de inicio vuelve a ella. Sin URL configurada, lo dice y ofrece abrir la configuración.
- **Atrás no sale.** Para ir a la configuración de Berry Med: **cinco toques en la esquina de arriba a la izquierda** en menos de 3 s y el **PIN del Panel Local** (`1515` por defecto). Al volver, el kiosco sigue donde estaba.
- La página tiene JavaScript, almacenamiento, cookies (también de terceros) y reproducción sin toque. El navegador se presenta como Chrome para Android (sin la marca `wv` de WebView). Cámara y micrófono se le dan sólo al origen de la URL del kiosco y a los de Zoom (`zoom.us`, `zoom.com`, `zoomgov.com`), y sólo si Android ya se los dio a Berry Med.
- En `adb logcat -s BerryMed`, las líneas `[KIOSCO]`: qué origen se abrió, permisos negados y los errores de la consola de la página (sin tokens). Con el APK de prueba (debug), la página se inspecciona desde el Chrome de la computadora en `chrome://inspect`.

## Teleconsulta: otoscopio y estetoscopio

Durante la teleconsulta de OnDoctor el médico ve el otoscopio y escucha el estetoscopio del tótem. La videollamada sigue siendo la de Zoom, ahora dentro del kiosco de Berry Med; los instrumentos van aparte, por la sala de LiveKit del encuentro (`encounter-{id}`). No hace falta ningún cambio en la API, en el kiosco ni en la app del médico: es lo mismo que hacía la app del proveedor.

1. Al crear el encuentro, el kiosco navega a una dirección con `serverUrl` y `token` (`/{slug}/waiting-room?serverUrl=…&token=…`, `/triage-manual?…` o `/cmd/sessionResume?…`; también se acepta `koplak://auth?serverUrl=…&token=…`). Berry Med toma el token de esa dirección y **entra a la sala enseguida, sin publicar nada**: el token sirve 15 minutos para entrar, y una vez adentro LiveKit lo renueva. `serverUrl` es el subdominio de LiveKit Cloud: si no es una dirección, el servidor es `wss://{serverUrl}.livekit.cloud`.
2. En el kiosco, el paciente toca **Encender** en la tarjeta del otoscopio o del estetoscopio. La API manda `start_oth` o `start_steth` por el canal del tótem y Berry Med **publica** la pista: el video del otoscopio o el audio del estetoscopio. Si el puerto del YKUSH3 del instrumento estaba apagado lo enciende (el del auricular no), y al terminar apaga sólo lo que encendió.
3. Uno a la vez, como en el kiosco: encender uno despublica el otro. **Apagar** (`stop_oth`, `stop_steth`) y el `stop-monitoring` (que llega también al terminar los signos, antes de la videollamada) **despublican y siguen en la sala**.
4. **Sale de la sala** cuando el kiosco muestra un token nuevo (otro paciente: sale y entra con el nuevo), cuando vuelve al inicio, al login o al setup (`/{slug}`, `/{slug}/login`, `/{slug}/setup`) o a las 3 horas. Si la sala se cae y LiveKit no la recupera, vuelve a entrar una sola vez, si no pasaron 15 minutos desde que vio el token.

Detalles:

- **Otoscopio**: elige el camino como el Panel Local. Por **UVC** (preferido: va directo por USB y no compite con la cámara de la tablet que usa Zoom) o por **Camera2**. Va a 800×800 o lo que entregue el otoscopio, hasta 30 cuadros por segundo, unos 1,5 Mbps, sin simulcast y sin achicar la imagen si falta ancho de banda (se bajan los cuadros).
- **Estetoscopio**: sólo el adaptador por su nombre configurado, nunca el micrófono de la tablet ni el del auricular. Sin procesamiento de voz (eco, ruido, ganancia automática y pasa-altos apagados), fuente `UNPROCESSED` si la tablet la tiene (si no, `MIC`), con el filtro Amplio (20 a 1000 Hz) y el volumen por defecto del Panel Local, en Opus a 64 kbps sin DTX. Berry Med no toca el modo, el foco ni el ruteo del audio.
- **El micrófono se comparte.** Como el kiosco y el estetoscopio son la misma app, Android no silencia la captura del estetoscopio aunque Zoom tenga el micrófono abierto (ver [`docs/hardware.md`](docs/hardware.md#el-micrófono-compartido)). Testing muestra si Android silencia la captura.
- **Las órdenes sin sala** (el kiosco no mostró un token) o de otro encuentro se ignoran y quedan en la consola. **Con el Panel Local abierto** también, y abrir el panel despublica lo que haya: los instrumentos son del panel.
- **Requisitos**: el tótem tiene que tener el otoscopio o el estetoscopio habilitados en el panel de administración de OnDoctor (si no, el kiosco no muestra los botones), los permisos de cámara y micrófono otorgados y el kiosco como app de inicio (así el servicio tiene cámara y micrófono desde el arranque).
- **Qué mirar**: Testing → **Teleconsulta (LiveKit)** y, en `adb logcat -s BerryMed`, las líneas `[TELE]` (token visto, sala, qué publica, por qué no; nunca el token) y `[PUSHER]` (qué orden llegó y de qué encuentro).
- La sala no se suscribe a nada y Berry Med no reproduce nada de LiveKit. No graba ni manda el audio o el video a otro lado.

## Probar el Panel Local en el tótem

Con el armado completo (ver [`docs/hardware.md`](docs/hardware.md)), la tablet conectada a una computadora y, en otra terminal, `adb logcat -s BerryMed` andando. En este orden:

1. **Testing → Dispositivos que ve Android**, con todo enchufado y los tres puertos del YKUSH3 encendidos (Leer estado; si alguno está apagado, Encender):
   - que aparezcan las dos placas de audio USB, el adaptador del estetoscopio y el auricular H390, y anotar con qué nombre las lista Android: son los que van en Configuración → Plaqueta → Estetoscopio si no coinciden con `USB Audio` y `H390`;
   - en Cámaras, si el otoscopio aparece como cámara externa ("externa (USB)") o sólo como dispositivo USB.
2. **Panel Local con la plaqueta**: ECG (las derivaciones barriendo, señal buena con los electrodos puestos), SpO₂ con el dedo en el sensor, respiración, presión (Medir, ver el manguito inflando y el resultado con la hora) y termómetro (apretar el gatillo y que llegue la lectura).
3. **Estetoscopio**: Escuchar y comprobar que suena por el H390 sin acople; con el estetoscopio en el pecho, que se vea la onda; anotar qué volumen y qué filtro hacen falta; Grabar 15 s y reproducir la grabación por el auricular.
4. **Otoscopio**: Encender, que prenda la luz y aparezca la imagen; anotar por qué camino fue (el encabezado dice "En vivo · Camera2" o "En vivo · UVC"); Congelar y Guardar captura; Apagar. Al salir del panel, el puerto del otoscopio tiene que quedar como estaba antes de abrirlo (con Leer estado en Testing).
5. **Al salir**, que el kiosco y la teleconsulta sigan andando: los puertos que estaban encendidos siguen encendidos y la consulta envía como siempre.

Si algo falla, en `adb logcat -s BerryMed`:

| Qué falla | Qué buscar |
|---|---|
| El hub no cambia un puerto | Líneas `[YKUSH]`: "El hub no contesta" o "El hub rechazó". |
| El otoscopio no aparece o no da imagen | Líneas `[OTOSCOPIO]`: si apareció y por qué camino se abrió, "No apareció en 8 s", "Está enchufado pero sin permiso USB", "No abrió …" con el motivo. |
| El estetoscopio no suena o no hay onda | Líneas `[ESTETO]`: "Escuchando por …" dice qué entrada y qué salida eligió; "No se pudo abrir el auricular" o "la captura" con el error. |
| La plaqueta, la presión o el termómetro | Líneas `[USB]` (puerto de la plaqueta), `[NIBP]` (presión) y `[THERM]` (termómetro). |
| El panel no abre o se ignoran órdenes | Líneas `[LOCAL]` y `[SESIÓN]`. |
| La teleconsulta no muestra el otoscopio o el estetoscopio | Líneas `[TELE]` y `[PUSHER]`, y Testing → Teleconsulta (LiveKit). |
| El kiosco no abre, no pide cámara o la videollamada falla | Líneas `[KIOSCO]`, y la consola de la página con el APK de prueba (ver [Kiosco](#kiosco)). |

Lo que el emulador no puede mostrar está en "Riesgos a confirmar en la tablet real" de [`docs/hardware.md`](docs/hardware.md#riesgos-a-confirmar-en-la-tablet-real): si el otoscopio aparece por Camera2 o sólo por UVC, si las dos placas de audio conviven y con qué nombres, el ancho de banda con otoscopio y audio a la vez en el mismo hub, que `setPreferredDevice` enrute de verdad al adaptador y al auricular, y el permiso USB del otoscopio negado sin diálogo si falta el de cámara.

## Qué no hace esta versión

No publica `/health` y no usa Bluetooth. La aplicación web no cambia: cuando el `POST` llega, sigue igual que con el exe. Ojo con el volumen: el exe por USB pierde una de cada dos tramas, así que esta app manda el doble de muestras de ECG que un tótem con Windows.

## Compilar

Abrir la carpeta en Android Studio y ejecutar el módulo `app`, o desde la terminal:

```bash
./gradlew test assembleDebug
```

Hace falta JDK 17 y el Android SDK 35. En una Mac con Homebrew, el JDK 17 se apunta con `JAVA_HOME`:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew test assembleDebug
```

Los tests no necesitan la plaqueta. `local.properties` (la ruta del SDK) es de cada máquina y no se commitea.

Cada versión publicada tiene su APK en [Releases](https://github.com/Guardia-Virtual-Medica/berry-med-android/releases), con el SHA-256 en las notas. No hay CI: el APK de la release se compila a mano (`assembleDebug`) y se sube con `gh release create v<versionName> <apk>`. Para instalarlo en la tablet conectada por USB:

```bash
adb install -r berry-med-1.2.2.apk
```

O, recién compilado:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El APK es debug y lleva la clave debug de la máquina que lo compiló. Siempre firmado con la misma clave: si cambia, hay que desinstalar y eso borra la configuración (ver [Instalar y dejarla sola](#instalar-y-dejarla-sola)).

Para probar sin tablet está el emulador `berry_tablet`: Android 15, 2560×1600 a 320 dpi, que son los 1280×800 dp para los que está pensado el Panel Local.

## Licencia

GNU GPLv3, igual que el monitor de Windows del que sale el protocolo. Ver [LICENSE](LICENSE).
