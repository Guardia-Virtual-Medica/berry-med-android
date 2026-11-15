# Handoff: panel lateral de estado (tablet Android)

## Overview

Una columna fija a la derecha de la tablet con cuatro cosas siempre a la vista:

1. **Wi-Fi**: la red y la señal.
2. **Modo**: Remoto (kiosco ONDOCTOR365, teleconsulta) o Local (Panel Local, presencial), con el botón para pasar al otro.
3. **Dispositivos**: SpO2, ECG, Presión, Temperatura, Otoscopio, Estetoscopio y el hub USB, cada uno en verde o en rojo.
4. **Sonido de la llamada**: Auricular o Tablet.

Es **nativo y va fijo en el código**. Se ve en los dos modos: al lado del WebView de ONDOCTOR365 en `KioskActivity` y al lado del Panel Local en `LocalPanelActivity`. Al abrir la app siempre aparece **abierto**. Se puede cerrar a un riel de 64 dp, y cerrado dura hasta que se cierra la app.

Sale del boceto a mano, del lo-fi y del hi-fi:

- Lo-fi: https://claude.ai/artifact/ALB2b5DYX8E1qv5KnuedYC
- Hi-fi: https://claude.ai/artifact/UzzUXJJUWAoWcK52ZzrLPm

Los dos son privados: para que otra persona los vea hay que compartirlos desde la página.

La implementación está en `docs/planes/plan-16-panel-lateral.md`.

## Archivos

- `Panel Lateral.html`: las seis pantallas en hi-fi. Se abre en Chrome desde el repo y toma las fuentes de `app/src/main/res/font`. Los íconos vienen de Google Fonts, así que hace falta red. **No es código para copiar**: es la referencia visual.
- `screenshots/`: las seis pantallas a 1280 × 800, la resolución nativa.
  - `01` Remoto abierto
  - `02` Remoto cerrado
  - `03` cambio de modo
  - `04` Local bloqueado (PIN)
  - `05` Local abierto
  - `06` Local cerrado

## Fidelity

**Alta fidelidad.** Colores, tipografía, medidas y textos son finales. Todo usa los tokens que ya están en `res/values/colors.xml` y las fuentes de `res/font`: no hay colores ni fuentes nuevos.

Se diseñó a **1280 × 800 dp**, la tablet de 10" en horizontal. El panel y el riel tienen ancho fijo, y lo que se estira es el contenido de la izquierda.

---

## Layout

```
REMOTO ABIERTO                                   REMOTO CERRADO
┌──────────────────────────────────┬─────────┐   ┌────────────────────────────────────────┬──┐
│ WebView ONDOCTOR365 (984 dp)     │ PANEL   │   │ WebView ONDOCTOR365 (1216 dp)          │R │
│                                  │ 296 dp  │   │                                        │64│
└──────────────────────────────────┴─────────┘   └────────────────────────────────────────┴──┘

LOCAL ABIERTO (984 dp para el Panel Local)
┌──────────────────────────────────────────────────────────┬─────────┐
│ Cabecera 64: hora · fecha ·················· [Finalizar] │ PANEL   │
├─────────────────────────────────────┬────────────────────┤ 296 dp  │
│ ECG 636 dp (toolbar en dos filas)   │ Vitales 312 dp     │         │
│ Dock                                │                    │         │
└─────────────────────────────────────┴────────────────────┴─────────┘
```

- **Empuja, no tapa.** El panel ocupa su ancho y el contenido se achica: 984 dp con el panel abierto y 1216 dp con el riel. Nada del kiosco ni del Panel Local queda debajo.
- **En Local la cabecera del Panel Local pierde los seis chips** de ECG, SpO2, PA, Temp, Otoscopio y Estetoscopio. El panel lateral los reemplaza. Quedan la hora, la fecha y Finalizar.
- **ECG con el panel abierto.** Mide 636 dp y la toolbar no entra en una fila, así que pasa a dos:
  - arriba (46 dp): «ECG», la señal, un espaciador y Congelar;
  - abajo: los tres segmentados (derivaciones, velocidad y ganancia) con sus unidades.
  - Padding de la toolbar: 8 dp arriba, 12 dp a la derecha, 10 dp abajo y 18 dp a la izquierda. Gap de 8 dp entre filas.
  - Con el riel, el ECG mide 868 dp y la toolbar vuelve a una fila, como hoy.
- **La pantalla de PIN** del Panel Local (`lock`) y la capa de Finalizar (`finishLayer`) tapan sólo el Panel Local, no el panel lateral.

## Panel abierto (296 dp)

- **Contenedor:**
  - fondo `surface` #11171E, borde izquierdo de 1 dp `line` #1E2731, sin sombra;
  - columna vertical: cabecera, Modo, Dispositivos, un espaciador flexible y Sonido.
- **Separadores:** 1 dp `line` entre secciones. Sonido lleva el separador arriba y queda pegado abajo.
- **Sección:** padding de 16 dp arriba y abajo, 16 dp a los costados.
- **Etiqueta de sección:** Barlow 600, 12 sp, letter-spacing 0.08 em, mayúsculas, color `muted` #8A97A6, 12 dp hasta el contenido. Es el mismo estilo que «CAPTURAS · N».

### 1. Cabecera con Wi-Fi (64 dp)

- **Medidas:** padding de 16 dp a la izquierda y 12 dp a la derecha, borde inferior de 1 dp `line`, gap de 12 dp.
- **Caja de ícono:**
  - 40 × 40 dp, radio 11, fondo `surface2` #1A232D, ícono de 22 dp en `text2` #C9D2DC;
  - sin red: fondo `alarmBg` #2A1013 e ícono en `alarmText` #FF8A8A.
- **Textos:**
  - nombre de la red: Barlow 600, 16 sp, `text` #E8EDF2, una línea con elipsis;
  - estado debajo: Barlow 500, 13 sp.
- **Barras de señal:**
  - cuatro barras de 4 dp de ancho y 5, 8, 12 y 16 dp de alto, gap de 3 dp, radio 1;
  - encendidas en `text2` (en `warn` si la señal es débil), apagadas en `line3` #3A4654.
- **Botón cerrar:** 44 × 44 dp, radio 10, fondo `surface2`, borde 1 dp `line2` #26313D, ícono `chevron_right` en `text2`. Cierra al riel.

| Estado | Ícono | Nombre | Estado (13 sp) | Barras |
|---|---|---|---|---|
| Señal buena | `wifi` | SSID | «Señal buena», `muted` | 4 o 3, `text2` |
| Señal débil | `network_wifi_1_bar` | SSID | «Señal débil», `warn` #FFB020 | 1, `warn` |
| Sin internet (Wi-Fi sin validar) | `wifi` | SSID | «Sin internet», `warn` | según nivel, `warn` |
| Sin red | `wifi_off` en caja `alarmBg` | «Sin Wi-Fi» | «Tocá para elegir una red», `alarmText` | no hay; va un `chevron_right` en `faint` |
| Sin permiso de ubicación | `wifi` | «Wi-Fi» | según señal | según nivel |

- **Sin red**, toda la cabecera (menos el botón cerrar) abre el panel de Wi-Fi de Android.
- **Nivel de señal → barras:** 4 barras desde −60 dBm, 3 hasta −70, 2 hasta −80 y 1 por debajo. «Señal débil» es 1 barra.

### 2. Modo

- **Fila:**
  - cuadro de 40 × 40 dp, radio 11, gap de 12 dp:
    - Remoto: fondo `ondoctor_icon` #5B3FA8 con ícono `video_call`;
    - Local: fondo `local_icon` #1E4E8C con ícono `stethoscope`;
    - el ícono va en `text`.
  - nombre del modo en Barlow 700, 18 sp, `text`;
  - debajo, en Barlow 500, 13 sp, `muted`:
    - Remoto: «ONDOCTOR365 · teleconsulta»;
    - Local: «Panel Local · presencial»;
    - Local con el PIN pendiente: «Panel Local · esperando el PIN».
- **Botón** (12 dp debajo de la fila):
  - 52 dp de alto, todo el ancho, radio 12, fondo `surface2`, borde 1 dp `line2`;
  - ícono `swap_horiz` de 22 dp en `text2` + Barlow 600, 15 sp, `text`;
  - pressed: fondo `surfaceActive` #18212B.
  - **No es `primary`:** el panel no compite con la acción principal del Panel Local.
  - Texto según el modo:
    - Remoto: «Cambiar a Panel Local»;
    - Local: «Cambiar a ONDOCTOR365»;
    - Local con el PIN pendiente: «Volver a ONDOCTOR365».
- **Bloqueado** (consulta en curso, ver el plan):
  - el botón va sin fondo, con borde punteado de 1.5 dp en `dashed` #2E3946, ícono `lock` y texto en `faint` #5B6775, y no responde;
  - debajo, a 10 dp: ícono `info` de 16 dp + «Hay una consulta en curso. Terminala para cambiar.», Barlow 500, 13 sp, `muted`.

### 3. Dispositivos

- **Conteo** a la derecha de la etiqueta:
  - píldora de 26 dp de alto, padding 0 × 10 dp, radio 13, Barlow Semi Condensed 600, 14 sp, numerales tabulares;
  - cuenta los verdes sobre 7 («6/7»);
  - colores:
    - todos verdes: texto `ok` con borde 1 dp `line2`;
    - algún rojo: fondo `alarmBg` y texto `alarmText`;
    - sólo ámbar: texto `warn` con borde `line2`.
- **Fila:**
  - 44 dp, padding de 10 dp con margen de −10 dp (para que el fondo de la fila mala no se corte), radio 10, gap de 12 dp;
  - punto de estado de 8 dp;
  - nombre en Barlow 500, 15 sp, `text2`;
  - estado a la derecha en Barlow 500, 13 sp.
- Orden fijo: SpO2, ECG, Presión, Temperatura, Otoscopio, Estetoscopio, Hub USB.

| Tono | Punto | Fila | Estado |
|---|---|---|---|
| Verde | lleno `ok` #45D483 | sin fondo | `muted`: «Conectado», «Listo», «En vivo» |
| Ámbar | lleno `warn` #FFB020 | sin fondo | `warn`: «Conectando», «Electrodos sueltos» |
| Rojo | **aro hueco** de 2 dp `alarm` #FF4D4F | fondo `alarmBg`, nombre en `text` | `alarmText`: «Desconectado», «Sin hub», «Sin permiso USB» |

El rojo es aro y el verde punto lleno: se distinguen por la forma, no sólo por el color. En el Panel Local `alarm` es sólo para alarmas clínicas. El panel lateral es la única excepción, porque así se pidió: un instrumento desconectado es algo para resolver antes de atender.

Las reglas de qué estado lleva cada dispositivo están en el plan, punto 3.

### 4. Sonido de la llamada

- **Etiqueta:** «SONIDO DE LA LLAMADA».
- **Segmentado** con el estilo del Panel Local:
  - contenedor con fondo `bg` #0B0F14, borde 1 dp `line`, radio 11, padding 3 dp, gap 2 dp;
  - dos segmentos iguales de 48 dp, radio 8, ícono de 20 dp + Barlow 600, 15 sp:
    - «Auricular» con `headset_mic`;
    - «Tablet» con `tablet_android`.
  - Elegido: fondo `text` #E8EDF2 y texto `bg`. No elegido: texto `muted`.
  - Sin nada en el jack: «Auricular» en `faint`, no se puede tocar, y queda elegida Tablet.
- **Ayuda** a 8 dp, Barlow 500, 13 sp, `muted`:
  - Auricular: «Se escucha y se habla por el auricular.»
  - Tablet: «Parlante y micrófono de la tablet.»
  - Sin auricular: «No hay auricular en el jack: se usa la tablet.»
  - En Local, todo eso va precedido por «Para la teleconsulta. ».

## Panel cerrado: el riel (64 dp)

- **Contenedor:** fondo `surface` y borde izquierdo de 1 dp `line`. Toda la columna es tocable y abre el panel.
- **Arriba:** cabecera de 64 dp con el botón de 44 dp (`chevron_left`) y borde inferior `line`.
- **Ítems:** de 48 × 56 dp, radio 11, 8 dp entre uno y otro; ícono de 22 dp en `text2` y punto de 8 dp arriba a la derecha (7 dp, 7 dp).
  1. **Wi-Fi:** `wifi`, `network_wifi_1_bar` o `wifi_off`. Punto verde, ámbar o rojo (aro). Sin red: fondo `alarmBg`.
  2. **Modo:** cuadro de 32 dp, radio 9, del color del modo, con su ícono de 20 dp.
  3. **Dispositivos:** `usb` + el conteo «6/7» en Barlow Semi Condensed 600, 13 sp. Con algún rojo: fondo `alarmBg`, texto en `alarmText` y aro rojo.
  4. **Sonido**, pegado abajo con borde superior `line`: `headset_mic` o `tablet_android`.
- **No se abre solo** cuando algo pasa a rojo: correría el WebView en medio de una llamada. Alcanza con el rojo del riel.

## Diálogos de cambio de modo

Llevan el estilo del diálogo de Finalizar que ya existe:

- scrim `scrim`;
- tarjeta de 440 dp, fondo `dialog` #131A22, borde 1 dp `line2`, radio 18, padding 28 dp, gap 12 dp;
- título en Barlow 600, 22 sp;
- cuerpo en 16 sp, line-height 1.45, color `body` #A9B4C0;
- botones de 48 dp a la derecha: «Cancelar» con contorno `line3` y la acción con fondo `text` y texto `bg` en Barlow 700.

| Cambio | Título | Cuerpo | Acción |
|---|---|---|---|
| Remoto → Local | ¿Pasar al Panel Local? | Se cierra el kiosco de ONDOCTOR365. El Panel Local te va a pedir el PIN. | Cambiar |
| Local → Remoto | ¿Volver a ONDOCTOR365? | Se finaliza el Panel Local: se detiene el ECG y se apagan el otoscopio y la cámara. Nada de lo medido sale a ONDOCTOR365. | Volver |

Desde la pantalla de PIN del Panel Local, «Volver a ONDOCTOR365» no pregunta: todavía no empezó nada.

## Íconos

Material Symbols Rounded, peso 400, sin relleno, importados como vectores (`res/drawable/ic_*.xml`, como los que ya usa el Panel Local):

`wifi`, `network_wifi_1_bar`, `network_wifi_2_bar`, `network_wifi_3_bar`, `wifi_off`, `video_call`, `stethoscope`, `swap_horiz`, `lock`, `info`, `headset_mic`, `tablet_android`, `usb`, `chevron_right`, `chevron_left`.

## Textos

En castellano y con voseo, con las mismas palabras que el Panel Local. Todos van a `res/values/strings.xml`. Los textos exactos están en las tablas de arriba.

## Pendiente de confirmar en la tablet

- Que el kiosco de ONDOCTOR365 se vea bien a 984 dp de ancho: la videollamada y los botones de la consulta.
- Que el ECG con siete derivaciones se lea bien a 636 dp.
- Los umbrales de dBm de las barras.
