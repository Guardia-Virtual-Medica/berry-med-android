# Decisiones del proyecto

Decisiones de producto y de alcance de Berry Med, en orden cronológico. El detalle de cada etapa está en `docs/planes/`, el armado en `docs/hardware.md` y la interfaz del Panel Local en `docs/redisenio/design_handoff_panel_medico/`.

## 28 de octubre de 2026

### Panel Local: alcance

- El Panel Local es para atención **presencial, sin teleconsulta**: el médico o el enfermero está al lado del paciente.
- No identifica al paciente.
- Es exclusivo con Chrome: una cosa o la otra. Tiene un ícono propio en la tablet, además del botón en la pantalla principal.
- No se guarda nada en la v1. Para una v2 queda preguntar si guardar y generar un PDF local. Nunca se envía nada a OnDoctor desde el panel.
- PIN de acceso `1515`, configurable; se pide al abrir y cada vez que el panel vuelve al frente.
- ECG con las 7 derivaciones, estilo papel con cuadrícula. Sólo visualización, sin alarmas.
- Las ondas se dibujan a mano con `Canvas`: las librerías de gráficos no llegan a 250 muestras por segundo por derivación (plan 1).

### Hardware

- Cámara: la de la tablet. Otoscopio por el hub USB YKUSH3. Estetoscopio por jack, a través de un adaptador de audio USB. Auricular USB Logitech H390. Tablet Android de última versión. Tal vez un ecógrafo en el futuro.
- El relé que cortaba los 12 V de la plaqueta ya no va: se corrigió el firmware.
- El estetoscopio suena sólo por el auricular; por el parlante de la tablet se acopla. El adaptador y el auricular se eligen por nombre, configurable, y el micrófono del auricular nunca hace de estetoscopio (plan 2).
- El otoscopio se pide a 800×800: a 1440² se come el ancho de banda del USB 2.0 que comparte con la plaqueta (plan 3).

### Forma de trabajo

- Los planes los escribe un modelo (Fable) y los ejecuta un agente Opus 5.5 con effort medio. Cada etapa es un PR que se revisa antes de mergear.

## 29 de octubre de 2026

### Estetoscopio y otoscopio

- Los planes 2 y 3 se arman completos sin esperar la prueba de hardware.
- El otoscopio tiene los dos caminos de video (Camera2/CameraX y UVC con libuvc) y elige solo según lo que vea la tablet; el panel dice cuál usó.
- El panel sólo apaga los puertos del YKUSH3 que encendió él. Un puerto que ya estaba encendido queda encendido al salir, para la teleconsulta. Si el hub no contestó la lectura del estado, enciende igual pero no apaga al salir.
- Otoscopio ampliado al centro de la pantalla con el resto desenfocado (plan 4, PR #12). Se eliminó después en el rediseño.

### LiveKit

- Hoy ningún programa del tótem publica el otoscopio ni el estetoscopio a la sala de LiveKit: la API emite tokens y la app del médico se suscribe, pero el kiosco no publica.
- Se decidió **no cambiar nada** por ahora y hacer foco en el Panel Local. La publicación desde Berry Med queda como plan 6, después.

### Envío de mediciones

- El tope de 30 minutos de la consulta no es necesario: se eliminó (PR #13). La consulta termina con `stop-monitoring` o con tres `404` seguidos.

### Rediseño del Panel Local (plan 5)

- Rediseño completo según el handoff de diseño hi-fi de `docs/redisenio/`, en tres etapas: 5a (PR #14), 5b (PR #15) y 5c (PR #16).
- Se aparta del handoff en:
  - Nada se guarda en disco: capturas y grabaciones viven sólo en memoria y se descartan al finalizar o al bloquearse el panel.
  - Sin alarmas: ni límites de FC ni colores de alarma.
  - Termómetro sin botones Conectar ni Medir: manda solo al apretar el gatillo.
  - ECG en milímetros reales, no "4 dp = 1 mm".
  - El PIN se mantiene.
  - Abrir un dispositivo no lo enciende y cerrarlo no lo apaga.
  - Se eliminó el otoscopio ampliado del plan 4: el dispositivo abierto ya es grande.
- Implementación de 5a:
  - Los cuatro paneles (ECG, otoscopio, estetoscopio, cámara) quedan superpuestos y el abierto pasa al frente; no se ocultan con `GONE`, para no cortar el video del otoscopio.
  - Ya no se muestran las ondas de SpO₂ y respiración ni la temperatura de la sonda de la plaqueta.
  - Bajo la FC va "Electrodos sueltos" o la arritmia que informa la plaqueta.
- Implementación de 5b: capturas en dos pasos (Congelar, después Guardar captura o Descartar y volver), las últimas cuatro en miniatura.
- Implementación de 5c:
  - Filtros Corazón (20 a 250 Hz), Pulmón (100 a 1000 Hz) y Amplio (20 a 1000 Hz); volumen de 0 a 10; grabación de 15 s; las últimas tres grabaciones se reproducen por el auricular.
  - La lista de grabaciones va debajo del fonocardiograma, por falta de alto.
  - El filtro queda fijo mientras graba.

### LiveKit: otoscopio y estetoscopio en la teleconsulta (plan 6)

- Reemplaza la decisión de más arriba ("no cambiar nada"): el médico de OnDoctor ve el otoscopio y escucha el estetoscopio del tótem durante la teleconsulta. La videollamada sigue en Zoom; los instrumentos van por la sala de LiveKit del encuentro. Plan en `docs/planes/plan-6-livekit-teleconsulta.md`, en tres partes: API (token para publicar), kiosco (suelta el micrófono de Zoom mientras suena el estetoscopio) y Berry Med (publica).
- Berry Med escucha `start_oth`, `stop_oth`, `start_steth` y `stop_steth` en el canal del tótem, pide el token a `POST {URL base}/{tótem}/livekit-token` y publica una pista a la vez. Con el Panel Local abierto las órdenes se ignoran.
- SDK `io.livekit:livekit-android` 2.29.0 (la última 2.x con más de dos semanas). Sin manejador de audio (`NoAudioHandler`), tipo de audio de medios y módulo de audio propio: Berry Med nunca toca el modo, el foco ni el ruteo del audio, que son de Zoom.
- Estetoscopio: fuente `UNPROCESSED` (o `MIC`), sin procesamiento de voz, filtro Amplio y volumen por defecto aplicados al buffer de captura, Opus a 64 kbps sin DTX.
- Otoscopio: el mismo camino que elige el Panel Local (UVC o Camera2), sin vista, 800×800 hasta 30 cuadros, 1,5 Mbps, sin simulcast, manteniendo la resolución.
- Servicio en primer plano: `connectedDevice` desde el arranque; suma `camera|microphone` sólo con una pantalla de Berry Med visible, y nunca baja. Al encender la tablet, si la app tiene "Mostrar sobre otras apps", abre la pantalla principal un momento y la manda atrás; si no, la notificación pide abrir Berry Med una vez.
- El APK sólo lleva ARM (`arm64-v8a`, `armeabi-v7a`) y las librerías nativas comprimidas: WebRTC trae cuatro arquitecturas y el APK pasaba de 12 a 70 MB; queda en 25 MB.
- No se cambia la app del médico. Nota: `useLiveKitDevices.ts` de `gvm.mt.doctors` espeja el video (`scaleX(-1)`) e invierte la imagen del otoscopio; es una línea si se decide sacarlo.

## Pendiente

- Prueba con el hardware real en la tablet (ver "Probar el Panel Local en el tótem" en el `README.md` y los riesgos de `docs/hardware.md`).
- Los hallazgos 8 a 26 de `docs/revisiones/revision-codigo-opus.md`.
- Plan 6 en la tablet real: el camino UVC sin vista, el adaptador con `UNPROCESSED`, el micrófono de Zoom en pausa y el reinicio con "Mostrar sobre otras apps" (ver "Qué probar en la tablet" en el PR).
- v2 con guardado y PDF local.
