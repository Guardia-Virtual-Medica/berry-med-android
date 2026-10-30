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
| Hub YKUSH3 (Yepkit) | HID `04D8:F11B`. Tres puertos USB que se encienden y apagan por comando. En qué puerto va cada equipo se configura en la pantalla (por defecto 1 otoscopio, 2 estetoscopio, 3 auricular). Se cambian a mano desde Testing, y el [Panel Local](#panel-local) enciende el del otoscopio con su botón Encender y los del estetoscopio y el auricular al tocar Escuchar, si estaban apagados. El panel sólo apaga los puertos que encendió él: uno que ya estaba encendido (como el auricular en reposo) queda encendido al salir. En reposo el otoscopio y el estetoscopio quedan apagados, y el auricular encendido sólo si la videollamada sale por él (ver [Hub YKUSH3 en Testing](#testing)). Si el hub no contestó la lectura del estado, el panel enciende el puerto igual pero no lo apaga al salir: prefiere dejarlo prendido de más que dejar a la teleconsulta sin el equipo. Cada equipo va en un puerto distinto; la pantalla no deja guardar dos en el mismo. |
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

## Configuración paso a paso

Este procedimiento deja una tablet lista para el tótem. Hacé las tareas en orden. Hacé un solo paso por vez. La sección [Configuración](#configuración) explica cada campo.

Estas palabras aparecen antes del paso al que se aplican:

- **PRECAUCIÓN**: si no cumplís la instrucción, podés dañar un equipo, perder datos o interrumpir una consulta.
- **NOTA**: da información útil para el paso que sigue.

### Antes de empezar

Prepará estos equipos:

- La tablet Android.
- El dock USB-C con carga.
- La plaqueta PM6750 y el termómetro.
- El hub YKUSH3 y su fuente de 5 V.
- El otoscopio.
- El adaptador de audio USB y el estetoscopio.
- El auricular Logitech H390.
- El APK de Berry Med.
- Una computadora con `adb`.

Pedí estos datos:

- El entorno de OnDoctor: Producción o Dev.
- El identificador del tótem (`external_id`).
- La URL del kiosco.
- La contraseña del tótem en el kiosco.

### Tarea 1: instalar Berry Med

**PRECAUCIÓN**: Instalá siempre un APK firmado con la misma clave. Con otra clave tenés que desinstalar la app, y eso borra la configuración.

1. Activá la depuración USB en la tablet.
2. Conectá la tablet a la computadora.
3. Ejecutá este comando en la computadora:

   ```bash
   adb install -r <ruta-del-apk>
   ```

4. Controlá que el comando muestre `Success`.
5. Desconectá la tablet de la computadora.

### Tarea 2: conectar los equipos

**PRECAUCIÓN**: Usá una fuente de 5,00 a 5,25 V para el hub. Otra tensión puede dañar el hub.

1. Poné el jumper de alimentación del hub en `VEXT`.
2. Conectá la fuente de 5 V al hub.
3. Conectá el dock a la tablet.
4. Conectá la alimentación del dock.
5. Conectá la plaqueta al dock.
6. Conectá el termómetro al dock.
7. Conectá el hub al dock con un cable USB 3.0 Micro-B.
8. Conectá el otoscopio al puerto 1 del hub.
9. Conectá el adaptador de audio al puerto 2 del hub.
10. Conectá el estetoscopio al jack del adaptador de audio.
11. Conectá el auricular H390 al puerto 3 del hub.

### Tarea 3: aceptar los permisos USB

**PRECAUCIÓN**: Marcá siempre "Usar de forma predeterminada". Sin esa casilla, Android muestra el pedido arriba del kiosco en cada reinicio.

**NOTA**: Android muestra un pedido por equipo: plaqueta, termómetro y hub YKUSH3. El orden puede cambiar.

1. Tocá el ícono de Berry Med.
2. Marcá "Usar de forma predeterminada" en el pedido.
3. Aceptá el pedido.
4. Hacé los pasos 2 y 3 en cada pedido que aparezca.

**NOTA**: Si la pantalla dice "Falta aceptar el permiso USB", tocá **Pedir permiso USB**.

### Tarea 4: completar el configurador

Paso Destino:

1. Tocá **Destino**.
2. En **Entorno**, elegí Producción o Dev.
3. En **Identificador del tótem**, escribí el `external_id`.
4. Tocá **Siguiente**.

Paso Órdenes:

5. Controlá que **Canal público** diga `totem.<external_id>`.
6. Tocá **Siguiente**.

Paso Plaqueta:

**NOTA**: Si nadie te indicó otro valor, dejá la ganancia en x1 y el modo en Monitor.

7. Controlá que **Puerto del otoscopio** diga 1.
8. Controlá que **Puerto del estetoscopio** diga 2.
9. Controlá que **Puerto del auricular** diga 3.
10. Tocá **Siguiente**.

Paso Kiosco:

11. En **URL del kiosco**, escribí la URL.
12. En **Audio de la videollamada**, elegí **Auricular** o **Parlante de la tablet**.
13. Tocá **Guardar y aplicar**.
14. Controlá que aparezca el mensaje "Configuración guardada. El tótem la usa desde ahora."

**NOTA**: Con el permiso del hub, Berry Med apaga los puertos del otoscopio y del estetoscopio. El puerto del auricular queda encendido sólo con la opción **Auricular**.

### Tarea 5: controlar los nombres de audio

**NOTA**: Android muestra un equipo de audio sólo con su puerto encendido.

1. Tocá **Testing**.
2. En **Hub YKUSH3**, tocá **Encender** en el puerto 2.
3. Si elegiste **Parlante de la tablet**, tocá **Encender** en el puerto 3.
4. Buscá la sección **Dispositivos que ve Android**.
5. En **Audio**, leé el nombre del adaptador.
6. En **Audio**, leé el nombre del auricular.
7. Tocá **Apagar** en el puerto 2.
8. Si elegiste **Parlante de la tablet**, tocá **Apagar** en el puerto 3.
9. Tocá **← Volver**.
10. Tocá **Plaqueta**.
11. Controlá que **Nombre del adaptador del estetoscopio** coincida con el nombre leído.
12. Controlá que **Nombre del auricular** coincida con el nombre leído.
13. Tocá **Kiosco**.
14. Si cambiaste un nombre, tocá **Guardar y aplicar**.

### Tarea 6: completar la puesta en marcha

Estos pasos están en la lista **Puesta en marcha** del paso Kiosco. El punto verde indica que el ítem está hecho. El punto ámbar indica que falta.

**NOTA**: Si un ítem tiene el punto verde, pasá al ítem siguiente.

1. En **Cámara y micrófono**, tocá **Dar permisos**.
2. Aceptá el permiso de la cámara.
3. Aceptá el permiso del micrófono.
4. En **Sin optimización de batería**, tocá **Evitar que se duerma**.
5. Aceptá el pedido de Android.
6. Controlá que **Servicio con cámara y micrófono para la teleconsulta** tenga el punto verde.

**NOTA**: El servicio suma la cámara y el micrófono solo. Si el punto sigue ámbar, volvé a abrir esta pantalla.

### Tarea 7: aceptar el permiso USB del otoscopio

**PRECAUCIÓN**: Hacé esta tarea después de dar el permiso de la cámara. Sin ese permiso, Android puede negar el pedido sin mostrarlo.

**PRECAUCIÓN**: Si no hacés esta tarea, el pedido aparece arriba del kiosco en la primera consulta.

1. En **Permiso USB del otoscopio**, tocá **Abrir Testing**.
2. En **Hub YKUSH3**, tocá **Encender** en el puerto 1.
3. Esperá el pedido de Android.
4. Marcá "Usar de forma predeterminada".
5. Aceptá el pedido.
6. Tocá **Apagar** en el puerto 1.

### Tarea 8: guardar el arranque del hub

**PRECAUCIÓN**: Hacé esta tarea sólo al instalar o al cambiar el audio de la videollamada. Esta orden escribe en la memoria del hub.

1. En Testing, tocá **Guardar este arranque en el hub**.
2. Controlá que la pantalla diga "El hub guardó el arranque".
3. Tocá **← Volver**.

### Tarea 9: preparar Android y OnDoctor

**PRECAUCIÓN**: Hacé esta tarea antes de elegir la app de inicio. Después, el kiosco ocupa toda la pantalla.

**NOTA**: Con un bloqueo seguro, Android no avisa el encendido y Berry Med no arranca.

1. Abrí los ajustes de Android.
2. Elegí un bloqueo de pantalla sin PIN, sin patrón y sin contraseña.
3. Sacá la app o el ajuste que abra Chrome al encender la tablet.
4. Si la marca tiene una lista propia de apps que se duermen, sacá Berry Med de esa lista.
5. En OnDoctor, habilitá el otoscopio o el estetoscopio para el tótem.
6. Volvé a Berry Med.

### Tarea 10: elegir Berry Med como app de inicio

**NOTA**: Después de esta tarea, Android abre el kiosco. Para volver a la configuración, tocá cinco veces la esquina de arriba a la izquierda. Después escribí el PIN del Panel Local.

1. En **Berry Med como app de inicio**, tocá **Elegir app de inicio**.
2. Elegí Berry Med.
3. Si Android pregunta, tocá **Siempre**.
4. Tocá el botón de inicio de la tablet.
5. Controlá que aparezca el kiosco.

### Tarea 11: configurar el tótem en el kiosco

1. En el kiosco, escribí el identificador del tótem.
2. Escribí la contraseña del tótem.
3. Confirmá el ingreso.

### Tarea 12: probar la tablet

1. Reiniciá la tablet con todos los equipos conectados.
2. Controlá que el kiosco aparezca solo.
3. Controlá que no aparezca un pedido de permiso USB.
4. Empezá una consulta de prueba.
5. Desconectá la plaqueta.
6. Conectá la plaqueta.
7. Controlá que la plaqueta vuelva sin tocar la pantalla.
8. Controlá que el kiosco siga adelante.

Para probar el audio y los instrumentos, seguí la sección [Probar el Panel Local en el tótem](#probar-el-panel-local-en-el-tótem).

### Si algo falla

| Problema | Causa posible | Acción |
|---|---|---|
| El kiosco dice "Falta la URL del kiosco". | La URL no está guardada. | Hacé la tarea 4, pasos 11 a 14. |
| La pantalla dice "Falta aceptar el permiso USB". | Un equipo no tiene permiso. | Tocá **Pedir permiso USB**. |
| El médico no ve el otoscopio. | El otoscopio no está habilitado para el tótem. | Hacé la tarea 9, paso 5. |
| El médico no ve el otoscopio. | El otoscopio no tiene permiso USB. | Hacé la tarea 7. |
| No se escucha la videollamada. | El auricular no está conectado. | Conectá el auricular al puerto 3 del hub. |
| Un puerto no cambia. | El hub no contesta. | Mirá las líneas `[YKUSH]` con `adb logcat -s BerryMed`. |

## Configuración

En la pantalla de Berry Med, en cuatro pasos:

- **Destino**: el entorno de OnDoctor, el identificador del tótem (su `external_id`) y la URL base de la API. Elegir el entorno completa la URL base, la clave y el cluster de Pusher; se pueden cambiar a mano antes de guardar:

  | Entorno | URL base | Clave de Pusher | Cluster |
  |---|---|---|---|
  | Producción | `https://portal.ondoctor365.com/api/v1/webhooks/totem` | `b5b07bcf4eb9935b29c7` | `sa1` |
  | Dev | `https://portal-dev.ondoctor365.com/api/v1/webhooks/totem` | `37b143a0f816ccba0eb2` | `sa1` |

  La URL base tiene que empezar con `https://` y no terminar en `/metrics`; la pantalla muestra cómo queda armada. La ruta `medical_devices` del `readme.txt` del exe es de la plataforma anterior. OnDoctor no pide usuario ni contraseña para recibir mediciones, así que el `POST` va sin autenticación.
- **Órdenes**: la clave de Pusher (viene del entorno), el cluster (un desplegable, `us2` o `sa1`; por defecto `sa1`), el canal y los nombres de los eventos de inicio y fin. El canal se arma solo como `totem.<external_id>` con el identificador de Destino, y lo sigue mientras nadie lo cambie a mano. Tiene que ser público: un canal `private-` o `presence-` no se conecta y la pantalla lo marca. La clave del exe viejo es de otra plataforma y no recibe las órdenes de OnDoctor.
- **Plaqueta**: la ganancia y el modo de ECG, la ganancia de respiración, el modo de presión y la presión de inflado. La ganancia y el modo se aplican apenas se guardan, sin reconectar. Abajo, en qué puerto del hub YKUSH3 va el otoscopio, el estetoscopio y el auricular (uno distinto para cada uno); bajo **Estetoscopio**, cómo se llaman para Android el adaptador y el auricular (se leen en Testing → Dispositivos que ve Android; por defecto `USB Audio` y `H390`); y el PIN del [Panel Local](#panel-local) (`1515` si se deja vacío).
- **Kiosco**: la **URL del kiosco** (la misma que abría Chrome, con el slug de la organización; vacía o `http(s)://` con dominio), el **audio de la videollamada** (Auricular, el de fábrica, o Parlante de la tablet; si el auricular no está, la llamada sale por el parlante, y el estetoscopio del Panel Local sale siempre por el auricular) y la **puesta en marcha**, una lista con un punto verde (hecho) o ámbar (falta) que se recalcula al volver a la pantalla:
  - **Cámara y micrófono**: los dos permisos otorgados. **Dar permisos** pide los que faltan; si Android ya no muestra el diálogo, abre los ajustes de la app.
  - **Berry Med como app de inicio**: el botón de inicio abre Berry Med. **Elegir app de inicio** abre la lista de apps de inicio de Android.
  - **Servicio con cámara y micrófono para la teleconsulta**: el servicio ya los sumó (en Android 10 o menos no hace falta). Se resuelve solo con esta pantalla adelante y los permisos dados.
  - **Sin optimización de batería**: lo mismo que **Evitar que se duerma**.
  - **Permiso USB del otoscopio**: Berry Med no lo puede saber. Como su puerto descansa apagado, hay que encenderlo una vez en Testing y aceptar el permiso marcando "Usar de forma predeterminada"; **Abrir Testing** lleva ahí.
  - **Configurar el tótem en el kiosco**: Berry Med no lo puede saber. La primera vez el kiosco pide el identificador y la contraseña del tótem, porque no usa la sesión de Chrome. **Abrir el kiosco** se habilita con la URL guardada.

  Abajo, lo que va **fuera de la tablet**: en OnDoctor, el otoscopio o el estetoscopio habilitados para el tótem; sacar lo que abra Chrome al prender (modo kiosco, app de administración); y cómo volver a la configuración desde el kiosco.

Todo queda guardado en la tablet. La pantalla también muestra los signos vitales, si la plaqueta está funcional, el último dato, la conexión con Pusher, el termómetro, el hub YKUSH3 y el resultado del último envío.

## Instalar y dejarla sola

Para preparar una tablet nueva, seguí [Configuración paso a paso](#configuración-paso-a-paso). Esta sección explica el motivo de cada requisito.

1. **Abrir Berry Med una vez después de instalarla.** Android no le avisa el encendido a una app que nunca se abrió.
2. **Aceptar el permiso USB de la plaqueta, el termómetro, el hub YKUSH3 y el otoscopio marcando "Usar de forma predeterminada".** Android da el permiso por cada conexión: después de un reinicio, cada equipo vuelve como un dispositivo nuevo. Con la casilla marcada lo da solo. Para eso abre una pantalla de Berry Med que no muestra nada y se cierra enseguida, así que el kiosco queda adelante. Sin la casilla, cada reinicio muestra un pedido de permiso arriba del kiosco. El adaptador del estetoscopio y el auricular no piden permiso: Android los maneja como audio. El otoscopio sí, por si la tablet no lo lista como cámara (ver [Panel Local](#panel-local)); como su puerto descansa apagado, el pedido no aparece solo: hay que provocarlo una vez, encendiendo el puerto en Testing → Hub YKUSH3 (el paso Kiosco lo recuerda y tiene el botón). Aceptar el permiso, marcar la casilla y apagar el puerto; si no se apaga, queda encendido hasta el próximo reposo.

   Conviene abrir el Panel Local una vez y aceptar el permiso de la cámara **antes** de aceptar el permiso USB del otoscopio: en Android 10 o más, Android puede negar sin diálogo el permiso USB de un dispositivo de video si la app todavía no tiene el de cámara.

   Cada equipo se pide una sola vez, para no tapar el kiosco una y otra vez. Si alguien rechaza o cierra el pedido, la pantalla de Berry Med dice "Falta aceptar el permiso USB" y se resuelve con **Pedir permiso USB**. Un equipo sin permiso no frena a los demás.
3. **Completar el paso Kiosco de la configuración** (ver [Configuración](#configuración)): cargar la URL del kiosco, guardar y dejar en verde la lista de puesta en marcha (permisos de cámara y micrófono, Berry Med como app de inicio, el servicio con cámara y micrófono y sin optimización de batería), abrir el kiosco para configurar el tótem y resolver lo que va fuera de la tablet. La optimización de batería es imprescindible: si Android cierra el servicio, sólo puede volver a levantarlo si la app está excluida.
4. **Sacar a Berry Med de las listas propias de la marca.** Algunas marcas tienen, además de la optimización de batería de Android, su propia lista de apps que se duermen.
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
- **Hub YKUSH3**: **Leer estado** consulta los tres puertos sin cambiarlos; **Encender** y **Apagar** conmutan cada puerto, y la pantalla dice qué equipo lleva cada uno según la configuración. Con una consulta enviando no se cambia ningún puerto. El servicio lleva los puertos al **estado de reposo** la primera vez que el hub se puede usar (con permiso) y cuando se guarda otra salida de audio: otoscopio y estetoscopio apagados, auricular encendido si la llamada sale por él y apagado si sale por el parlante; un puerto libre queda como está. Si hay una consulta enviando, una prueba de Testing, el Panel Local abierto o la teleconsulta publicando no toca nada y lo hace apenas se libere. **Guardar este arranque en el hub** guarda ese mismo estado en la memoria del hub, para los segundos entre que recibe energía y arranca el servicio; dice en pantalla si el hub aceptó. Sale sólo con ese botón: es persistente y no se puede leer antes.
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
  - El **otoscopio**: **Encender** prende su puerto en el YKUSH3, que es enchufarlo y prender su luz, y espera hasta 8 s a que Android lo vea. Si estaba apagado lo enciende; si ya estaba encendido no lo toca. Si no aparece, apaga el puerto si lo encendió el panel y lo dice; si aparece por USB sin permiso, vuelve a pedirlo, deja el puerto encendido y dice que falta aceptar el permiso USB. La imagen va en un círculo, a 800×800 o la más cercana que ofrezca (más grande se come el ancho de banda del USB que comparte con la plaqueta). **Congelar** deja la imagen quieta para guardarla o descartarla (ver Capturas) y **Apagar** suelta la cámara y apaga el puerto si lo encendió el panel. Mientras está encendido, la cámara de la tablet queda en pausa (Android no garantiza dos cámaras abiertas a la vez) y vuelve sola al apagarlo. Hay dos caminos para el video y se eligen solos: si la tablet lista el otoscopio como cámara externa, va **por Camera2**; si no, **por UVC**, directo por USB con su permiso. El encabezado dice cuál usó ("En vivo · UVC"). Como el dispositivo USB aparece un momento antes que la cámara, se esperan 2 s a que aparezca la cámara antes de ir por UVC. Sin YKUSH3 se intenta directo. Al salir del panel el puerto queda como estaba antes de abrirlo.
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
- **Estetoscopio**: sólo el adaptador por su nombre configurado, nunca el micrófono de la tablet ni el del auricular. Sin procesamiento de voz (eco, ruido, ganancia automática y pasa-altos apagados), fuente `UNPROCESSED` si la tablet la tiene (si no, `MIC`), con el filtro Amplio (20 a 1000 Hz) y el volumen por defecto del Panel Local, en Opus a 64 kbps sin DTX. Berry Med no toca el modo ni el foco del audio; el dispositivo de la llamada sí (ver **Audio de la videollamada**).
- **El micrófono se comparte.** Como el kiosco y el estetoscopio son la misma app, Android no silencia la captura del estetoscopio aunque Zoom tenga el micrófono abierto (ver [`docs/hardware.md`](docs/hardware.md#el-micrófono-compartido)). Testing muestra si Android silencia la captura.
- **Las órdenes sin sala** (el kiosco no mostró un token) o de otro encuentro se ignoran y quedan en la consola. **Con el Panel Local abierto** también, y abrir el panel despublica lo que haya: los instrumentos son del panel.
- **Requisitos**: el tótem tiene que tener el otoscopio o el estetoscopio habilitados en el panel de administración de OnDoctor (si no, el kiosco no muestra los botones), los permisos de cámara y micrófono otorgados y el kiosco como app de inicio (así el servicio tiene cámara y micrófono desde el arranque).
- **Qué mirar**: Testing → **Teleconsulta (LiveKit)** y, en `adb logcat -s BerryMed`, las líneas `[TELE]` (token visto, sala, qué publica, por qué no; nunca el token) y `[PUSHER]` (qué orden llegó y de qué encuentro).
- **Audio de la videollamada**: Berry Med fija el dispositivo de comunicación de Android en el elegido en el paso Kiosco y lo reafirma si el `WebView` lo cambia (a lo sumo una vez por segundo; tras cinco intentos seguidos deja de insistir, con una línea `[LLAMADA]`). Con el H390 se habla por el H390; con el parlante, por el micrófono de la tablet: Android los mueve juntos. Nunca elige el adaptador del estetoscopio. Sólo actúa con Android 12 o más y en modo comunicación: Testing → Teleconsulta muestra la salida elegida, el dispositivo actual y el modo. Sin ese modo la elección depende de los puertos en reposo, que dejan una sola salida USB o ninguna; encender el del estetoscopio en plena llamada es el riesgo principal. Falta confirmarlo en la tablet con Zoom (ver [`docs/hardware.md`](docs/hardware.md#cómo-se-elige-la-salida-de-la-llamada)).
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
adb install -r berry-med-1.3.0.apk
```

O, recién compilado:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El APK es debug y lleva la clave debug de la máquina que lo compiló. Siempre firmado con la misma clave: si cambia, hay que desinstalar y eso borra la configuración (ver [Instalar y dejarla sola](#instalar-y-dejarla-sola)).

Para probar sin tablet está el emulador `berry_tablet`: Android 15, 2560×1600 a 320 dpi, que son los 1280×800 dp para los que está pensado el Panel Local.

## Licencia

GNU GPLv3, igual que el monitor de Windows del que sale el protocolo. Ver [LICENSE](LICENSE).
