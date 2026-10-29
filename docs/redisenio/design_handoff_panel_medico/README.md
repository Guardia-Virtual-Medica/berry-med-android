# Handoff: Panel Local de dispositivos médicos (tablet Android)

## Overview
Rediseño del panel que se muestra en la tablet cuando se conectan los dispositivos médicos: ECG (7 derivaciones), oxímetro (SpO₂ y pulso), tensiómetro (PA), termómetro IR, otoscopio, estetoscopio digital y cámara.

El objetivo es que el panel sea **útil antes que lindo**:
- vitales siempre visibles y en posición fija, sin scroll;
- un solo dispositivo de imagen o sonido en foco a la vez;
- cada estado dice qué pasa y qué hacer;
- toda medición puntual queda con hora.

## About the Design Files
Los archivos de este paquete son **referencias de diseño hechas en HTML**: prototipos que muestran el aspecto y el comportamiento buscados. **No son código para copiar.**

La tarea es **recrear este diseño en la app Android existente (Kotlin)** con sus patrones y librerías.
- Si la app ya usa **Jetpack Compose**, implementarlo en Compose (recomendado).
- Si usa **Views/XML**, respetar ese stack. El ECG va como `View` custom con `onDraw`, o como `SurfaceView`.

No reemplazar la lógica existente de conexión con los dispositivos (Bluetooth/USB, Camera2, etc.). Solo cambian la capa de UI y el modelo de estado de la pantalla.

Para ver los prototipos, abrir los `.dc.html` en Chrome (necesitan `support.js` en la misma carpeta):
- `Panel Medico.dc.html`: **diseño final (hi-fi), interactivo.** Es la referencia principal.
- `Panel Medico - Wireframes UX.dc.html`: wireframes lo-fi con el diagnóstico de la pantalla actual y las alternativas evaluadas. Se eligió **2a**, y el patrón de "dispositivo abierto" es **2b**.
- `uploads/pasted-…png`: captura de la pantalla actual (antes).

## Fidelity
**Alta fidelidad (hi-fi).** Colores, tipografía, tamaños, espaciados y estados son finales: recrearlos con la mayor precisión posible.

El diseño está a **1280 × 800 dp** (tablet de 10" en horizontal). En otras tablets:
- el área izquierda es la que se estira;
- la columna de vitales se mantiene en 312 dp.

---

## Configuración de pantalla (Android)
- **Orientación** fija en landscape.
- **Modo inmersivo:** `WindowInsetsControllerCompat.hide(systemBars())` con `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`. El reloj de la cabecera reemplaza al de la barra del sistema.
  - Si no se usa modo inmersivo, **respetar los insets** (`WindowInsets.safeDrawing`). En la pantalla actual el reloj pisa el título.
- **Pantalla siempre encendida** mientras haya sesión: `FLAG_KEEP_SCREEN_ON`.
- **Tema** siempre oscuro, sin seguir el modo del sistema.
- **Touch targets** de 48 dp como mínimo. Algunos segmentos miden 40 dp de alto dentro de un contenedor de 46 dp: dejar el área táctil en 48 dp con `Modifier.minimumInteractiveComponentSize()`.

---

## Layout (1280 × 800 dp)

```
┌──────────────────────────────────────────────────────────────────────────┐
│ CABECERA 64dp: [12:25  Miércoles 29 de octubre de 2026]   [chips…] [Finalizar] │
├───────────────────────────────────────────────────────┬──────────────────┤
│ ÁREA PRINCIPAL (flex)                                 │ VITALES 312dp     │
│  ┌ Panel ECG (o dispositivo abierto) ───────────────┐ │ ┌ FC (flex 1.65) ┐│
│  │ toolbar 64dp                                     │ │ ├ SpO₂ (1)       ┤│
│  │ trazado                                          │ │ ├ Resp (0.85)    ┤│
│  └──────────────────────────────────────────────────┘ │ ├ PA (1.1)       ┤│
│  [Dock 80dp: Otoscopio | Estetoscopio | Cámara]       │ └ Temp (0.95)    ┘│
└───────────────────────────────────────────────────────┴──────────────────┘
```

- **Raíz:** fondo `bg` (#0B0F14), columna vertical.
- **Cabecera:**
  - 64 dp de alto, padding horizontal 24 dp (izq.) y 16 dp (der.), borde inferior 1 dp `line` (#1E2731).
- **Cuerpo:**
  - grilla de 2 columnas, `1fr` y `312dp`;
  - gap 12 dp, padding 12 dp.
- **Columna izquierda:**
  - columna con gap 12 dp;
  - panel principal con `weight(1f)`;
  - dock de 80 dp fijo abajo.
- **Columna de vitales:**
  - columna con gap 10 dp;
  - 5 tarjetas con pesos 1.65 / 1 / 0.85 / 1.1 / 0.95;
  - **nunca scrollea**.

### Tarjeta (patrón común)
- Fondo `surface` #11171E, borde 1 dp `line` #1E2731, radio 14 dp.
- Padding de las tarjetas de vitales: 12–14 dp arriba y abajo, 18 dp a la izquierda, 12–18 dp a la derecha.

---

## Componentes

### 1. Cabecera
- **Hora:** `HH:mm` (24 h, es-AR), Barlow Semi Condensed 600, 30 sp, line-height 1, numerales tabulares. Se actualiza cada segundo.
- **Fecha:** formato largo es-AR con mayúscula inicial y sin coma ("Miércoles 29 de octubre de 2026"), Barlow 500, 16 sp, color `muted` #8A97A6. Separada de la hora por 12 dp y alineada por baseline.
- **Chips de estado de dispositivos:** ECG, SpO₂, PA, Temp, Otoscopio, Estetoscopio. Gap 8 dp.
  - **Medidas:** 30 dp de alto, padding horizontal 12 dp, radio 15 dp, borde 1 dp. Punto de 8 dp + texto Barlow 500 14 sp, separados por 7 dp.
  - **Conectado:** borde #26313D, texto #C9D2DC, punto `ok` #45D483.
  - **Desconectado:** borde #1E2731, texto `faint` #5B6775, punto #3A4654.
  - **Conectando:** punto `warn` #FFB020.
  - Son configurables (pueden ocultarse).
- **Botón "Finalizar":**
  - **Medidas:** 44 dp de alto, padding 0 × 20 dp, radio 10 dp. Borde 1 dp #3A4654, fondo transparente, Barlow 600 16 sp. Pressed: fondo #1A232D.
  - **Comportamiento:** abre un diálogo de confirmación (ver 6).

### 2. Panel ECG (vista por defecto del área principal)

**Toolbar** (64 dp, padding 0 12 0 18, borde inferior `line`, gap 16 dp):
- **Título:** "ECG", Barlow 600 18 sp, letter-spacing 0.03 em.
- **Estado de señal:** punto de 8 dp + "Señal buena", Barlow 500 13 sp, color `ok`. Estados posibles: Señal buena / Señal débil (warn) / Sin señal (faint).
- **Espaciador** flexible.
- **Tres controles segmentados**, todos con el mismo estilo:
  - **Contenedor:** fondo `bg` #0B0F14, borde 1 dp `line`, radio 11 dp, padding 3 dp, gap 2 dp.
  - **Segmento:** 40 dp de alto, ancho mínimo 46 dp, padding horizontal 10 dp, radio 8 dp, Barlow Semi Condensed 600 15 sp.
  - **Seleccionado:** fondo #E8EDF2, texto #0B0F14 (alto contraste, a propósito).
  - **No seleccionado:** fondo transparente, texto `muted` #8A97A6.
  - **Opciones:**
    - Derivaciones: `II` / `3` / `7` (por defecto `7`). `3` = I, II, III. `7` = I, II, III, aVR, aVL, aVF, V.
    - Velocidad: `12,5` / `25` / `50` + etiqueta "mm/s" (13 sp `muted`). Por defecto 25.
    - Ganancia: `5` / `10` / `20` + etiqueta "mm/mV". Por defecto 10.
- **Botón Congelar / Reanudar:**
  - **Medidas:** 46 dp de alto, padding 0 16 0 12, radio 10 dp, ícono de 22 dp + texto Barlow 600 15 sp.
  - **En vivo:** borde #3A4654, fondo transparente, ícono `pause`, texto "Congelar".
  - **Congelado:** fondo y borde #FFB020, texto #0B0F14, ícono `play_arrow`, texto "Reanudar".

**Área de trazado:**
- **Fondo y grilla:**
  - fondo #0D1318;
  - grilla mayor cada 20 dp (= 5 mm), línea de 1 dp en #1A242D;
  - grilla menor cada 4 dp (= 1 mm) en #121A20.
- **Escala:** **4 dp = 1 mm**. Velocidad en dp/s = mm/s × 4. Amplitud en dp por mV = ganancia (mm/mV) × 4.
- **Filas:** N filas iguales (N = derivaciones visibles). Línea base al 58 % de la altura de cada fila.
- **Etiqueta de derivación:** arriba a la izquierda de cada fila (left 14 dp, top +6 dp), Barlow Semi Condensed 600 14 sp, `muted`.
- **Trazo:**
  - color `ecg` #45D483 (en alarma: #FF8A8A), 1.6 dp, uniones redondeadas;
  - **barrido** de izquierda a derecha, como un monitor de cabecera: se borra una franja de 18 dp por delante del cursor y al llegar al borde vuelve a x = 0.
- **Badge de congelado:**
  - arriba a la derecha (14 dp, 12 dp), 28 dp de alto, radio 14 dp;
  - fondo #FFB020, texto #0B0F14 Barlow 700 13 sp, letter-spacing 0.06 em;
  - texto "CONGELADO · HH:mm:ss" (hora en que se congeló).
- **Implementación en Compose:**
  - `Canvas` + buffer circular de muestras por derivación;
  - redibujo con `withFrameNanos`;
  - mejor dibujar sobre un `ImageBitmap` persistente para no repintar toda la curva en cada frame.

### 3. Dock de dispositivos (80 dp, 3 columnas iguales, gap 12 dp)
- **Tile:**
  - `surface` #11171E, borde 1 dp `line`, radio 14 dp, padding 0 14 0 12, gap 14 dp;
  - toda la tarjeta es tocable.
- **Ícono:** caja de 52 × 52 dp, radio 11 dp, fondo #1A232D, ícono de 26 dp color #C9D2DC.
  - Otoscopio: `hearing`. Estetoscopio: `stethoscope`. Cámara: `videocam`.
- **Textos:**
  - nombre en Barlow 600 17 sp;
  - estado en Barlow 500 13 sp ("En vivo" en `ok` / "Conectado" o "Lista" en `muted` / "Grabando · N s" en #FF6B6B).
- **Cheurón:** `chevron_right` de 24 dp, color `faint`.
- **Tile activo** (su dispositivo está abierto): fondo #18212B, borde `primary` #4C9BFF, ícono `close`. Tocarlo de nuevo cierra el dispositivo.

### 4. Dispositivo abierto (reemplaza al panel ECG)
Al tocar un tile del dock:
- el panel ECG se reemplaza por el panel del dispositivo;
- debajo aparece una **tira de ECG** de 72 dp;
- el dock sigue visible para pasar de un dispositivo a otro;
- **la columna de vitales no se mueve**.

**Cabecera del panel** (64 dp, padding 0 12, gap 14 dp):
- **Botón volver:** ícono `arrow_back` + "ECG", 44 dp de alto, borde #26313D, radio 10 dp.
- **Título:** Barlow 600 18 sp.
- **Estado:** punto de 8 dp + texto de 13 sp.

**Cuerpo:** grilla `1fr` + `208dp`, gap 12 dp, padding 12 dp.

**Otoscopio y cámara (vista de video):**
- **Visor:**
  - fondo #05080B, radio 10 dp;
  - otoscopio en máscara circular (diámetro 400 dp, centrada);
  - cámara en rectángulo con radio 8 dp;
  - el placeholder del prototipo es el lugar del stream de Camera2.
- **Flujo de captura en dos pasos.** Congelar y capturar *no* son acciones paralelas.

  1. **En vivo:**
     - **Botón principal "Congelar":**
       - **Medidas:** 68 dp de alto, radio 12 dp, Barlow 700 18 sp, ícono `pause` de 24 dp.
       - **Colores:** fondo `primary` #4C9BFF, texto #06101C.
     - **Ayuda** debajo, 13 sp `muted`: "Congelá la imagen para revisarla antes de guardarla."
     - **Botón secundario:** 52 dp, fondo #1A232D, borde #26313D, radio 12 dp, Barlow 600 16 sp.
       - Otoscopio: "Apagar" / "Encender" (`power_settings_new`).
       - Cámara: "Cambiar cámara" (`cameraswitch`), alterna trasera y frontal.
  2. **Congelado:**
     - el visor muestra el badge "CONGELADO · sin guardar" arriba a la izquierda (mismo estilo que en el ECG);
     - **principal:** "Guardar captura" (`save`);
     - **secundario:** "Descartar y volver" (`close`), vuelve al vivo sin guardar.
  3. **Al guardar:**
     - se guarda el frame congelado con timestamp;
     - vuelve solo al vivo;
     - muestra un toast centrado abajo en el visor: "Captura guardada · HH:mm:ss" (40 dp de alto, radio 20 dp, fondo #E8EDF2, texto #0B0F14 Barlow 600 15 sp), durante 2,2 s.
- **Lista de capturas** (abajo en la columna de controles):
  - **Etiqueta:** "CAPTURAS · N" en Barlow 600 12 sp, letter-spacing 0.08 em, `muted`.
  - **Miniaturas:** grilla de 2 columnas, gap 8 dp, cuadradas. Fondo #1A232D, borde #26313D, radio 8 dp, hora abajo a la izquierda (12 sp).
  - Se muestran las últimas 4.
  - **Vacío:** "Todavía no hay capturas" (14 sp, `faint`).

**Estetoscopio:**
- **Visor:** fonocardiograma en vivo sobre #05080B, trazo #8FB8FF de 1.2 dp, barrido a 160 dp/s. Etiqueta "FONOCARDIOGRAMA · FILTRO {corazón|pulmón|amplio}" (12 sp, `muted`).
- **Controles:**
  - **Filtro:** segmentado vertical Corazón / Pulmón / Amplio. Segmentos de 44 dp, mismo estilo de seleccionado que en el ECG.
  - **Volumen:** botones − y + de 52 × 52 dp; valor 0–10 en Barlow Semi Condensed 600 26 sp.
  - **Botón principal "Grabar 15 s"** (`mic`, fondo `primary`):
    - mientras graba: fondo `alarm` #FF4D4F, texto blanco, "Grabando… N s" (ícono `fiber_manual_record`);
    - al terminar, la grabación entra a la lista.
  - **Lista de grabaciones:** filas de 44 dp, fondo #1A232D, radio 10 dp. Contenido: `play_arrow` en #8FB8FF + "Grabación N · {filtro}" + hora. Se muestran las últimas 3.

**Tira de ECG** (72 dp, `surface`, radio 14 dp, padding 0 8 0 16, gap 14 dp):
- **A la izquierda** (70 dp de ancho): "ECG · II" en 13 sp `muted` y "FC {valor}" en Barlow Semi Condensed 600 18 sp, color `ecg`.
- **Trazo:** derivación II, 56 dp de alto, grilla de 20 × 14 dp. La amplitud se limita al 38 % del alto.
- **Botón "Ver ECG":** `open_in_full`, 52 dp, cierra el dispositivo.

### 5. Columna de vitales

**Estructura común de cada tarjeta:**
- **Fila superior** alineada por baseline: etiqueta del parámetro (Barlow 700 16 sp, letter-spacing 0.04 em, en el color del parámetro) + fuente u hora (13 sp `muted`) + espaciador + unidad (14 sp `muted`).
- **Valor grande** centrado verticalmente: Barlow Semi Condensed 600, numerales tabulares, en el color del parámetro.

**Cada tarjeta:**
- **FC** (lpm, "desde ECG"):
  - valor 112 sp, color #45D483;
  - pie: "Límites 50–120" (13 sp `muted`), alineado a la derecha.
  - **Alarma** (FC fuera de límites):
    - tarjeta con fondo #2A1013 y borde #FF4D4F;
    - valor y etiqueta en #FF8A8A;
    - chip "▲ ALTA" (26 dp, fondo #FF4D4F, texto blanco Barlow 700 13 sp) titilando a 1,2 s con `steps(2)`;
    - el trazo del ECG también pasa a #FF8A8A.
  - Una alarma de FC baja usaría "▼ BAJA" con el mismo estilo.
- **SpO₂** (%): valor 64 sp, color #3CC8E8. A la derecha: "Pulso" (13 sp `muted`) y su valor (Barlow Semi Condensed 600 26 sp, #C9D2DC).
- **Resp** (rpm): valor 52 sp, color #F2C94C.
- **PA** (mmHg, medición puntual), valor 52 sp, color #B9A6FF:
  - **Resultado:** "120/80", PAM entre paréntesis "(93)" en 20 sp `muted`, botón "Medir" (48 dp, fondo #1A232D, borde #3A4654). La fila superior muestra "HH:mm · hace N min" ("recién" si pasó menos de 1 min).
  - **Midiendo:** presión del manguito en vivo (44 sp, #E8EDF2), fase "Inflando…" / "Midiendo…", barra de progreso de 6 dp (fondo #1E2731, relleno #B9A6FF, progreso = presión / 175) y botón "Cancelar". La fila superior dice "midiendo".
- **Temp** (°C, termómetro IR), valor 52 sp, color #FF9F5A, decimal con coma ("36,6"):
  - **Desconectado:**
    - tarjeta con borde **punteado** 1.5 dp #2E3946 y sin fondo;
    - etiqueta y unidad en `faint`;
    - texto "Termómetro IR desconectado" (15 sp `muted`);
    - botón principal "Conectar" (48 dp, fondo `primary`).
  - **Conectando:** "Buscando termómetro IR…" + spinner de 16 dp en el botón ("Conectando").
  - **Conectado:** valor + "IR · HH:mm" + botón "Medir", con spinner mientras mide.

### 6. Diálogo "Finalizar"
- **Scrim:** rgba(3,5,8,.72).
- **Tarjeta:** 440 dp, fondo #131A22, borde #26313D, radio 18 dp, padding 28 dp, gap 12 dp.
- **Título:** "¿Finalizar la sesión?" (Barlow 600 22 sp).
- **Cuerpo** (16 sp, line-height 1.45, #A9B4C0): "Se detiene el ECG y se apagan el otoscopio y la cámara. Las capturas y mediciones de esta sesión quedan guardadas."
- **Botones** de 48 dp alineados a la derecha:
  - "Cancelar": contorno #3A4654;
  - "Finalizar sesión": fondo #E8EDF2, texto #0B0F14, Barlow 700.

---

## Interacciones y comportamiento
- **Reloj y fecha:** se refrescan cada segundo.
- **Vitales continuos** (FC, SpO₂, pulso, Resp): se muestran tal como llegan del dispositivo, conviene refrescar el texto a ~1 Hz. El ECG y el fonocardiograma se dibujan a la tasa de frames.
- **Cambiar derivaciones:** limpia el canvas y reinicia el barrido en x = 0.
- **Cambiar velocidad o ganancia:** se aplica al instante sobre el barrido en curso.
- **Congelar ECG:** detiene el dibujo (también en la tira) y guarda la hora. **No** detiene la adquisición de datos ni el cálculo de FC.
- **Abrir o cerrar dispositivo:** desde el dock (toggle), con "← ECG" o con "Ver ECG" en la tira. Al cambiar de dispositivo se descongela el video.
- **Color = estado clínico.** Cada parámetro tiene su color fijo, como en los monitores de cabecera:

  | Color | Uso |
  |---|---|
  | `alarm` #FF4D4F | Alarma (y nada más) |
  | `warn` #FFB020 | Congelado, conectando o señal débil |
  | `ok` #45D483 | Conectado o en vivo (coincide con el verde del ECG) |
  | `primary` #4C9BFF | Acción principal de cada contexto (una sola por panel) |

- **Mensajes:** escribir los estados siempre con las mismas palabras en todos los dispositivos: "desconectado", "Conectando", "En vivo", "midiendo".
- **Animaciones:**
  - spinner: rotación lineal de 0,8 s;
  - chip de alarma: opacidad 1 ↔ 0,25 a 1,2 s con `steps(2)`;
  - no hay otras transiciones decorativas.

## Modelo de estado (sugerido, un `ViewModel` + `StateFlow<PanelUiState>`)
```kotlin
data class PanelUiState(
  val now: Instant,
  val ecg: EcgState,                 // conexión, calidad de señal, leads (II|3|7), speedMmS (12.5|25|50), gainMmMv (5|10|20), frozenAt: Instant?
  val hr: Int?, val hrAlarm: Alarm?,  // Alarm = HIGH|LOW con límites
  val spo2: Int?, val pulse: Int?, val rr: Int?,
  val bp: BpState,                   // Done(sys, dia, map, at) | Measuring(cuff, phase) | Idle
  val temp: TempState,               // Disconnected | Connecting | Ready(value, at) | Measuring
  val openDevice: Device?,           // OTOSCOPE | STETHOSCOPE | CAMERA | null (=ECG)
  val otoscopeOn: Boolean, val cameraFacing: Facing,
  val videoFrozen: Boolean,          // solo en el dispositivo de video abierto
  val captures: Map<Device, List<Capture>>,
  val steth: StethState,             // filter (HEART|LUNG|WIDE), volume 0..10, recordingSecondsLeft, recordings
  val deviceLinks: Map<DeviceKind, LinkState>, // para los chips: CONNECTED | CONNECTING | DISCONNECTED
  val confirmFinish: Boolean,
)
```
Eventos: `SetLeads`, `SetSpeed`, `SetGain`, `ToggleEcgFreeze`, `OpenDevice(d)`, `CloseDevice`, `FreezeVideo`, `SaveCapture`, `DiscardFrozen`, `ToggleOtoscope`, `SwitchCamera`, `SetFilter`, `VolumeUp/Down`, `RecordSteth`, `MeasureBp`/`CancelBp`, `ConnectTemp`, `MeasureTemp`, `AskFinish`/`CancelFinish`/`ConfirmFinish`.

> En el prototipo los datos están simulados (ondas sintéticas y valores aleatorios). En la app, conectar cada campo a las fuentes reales que ya existen.

## Design tokens

Sugerido: un `object PanelColors` / `PanelTheme` en Kotlin.

**Neutros y superficies**

| Token | Hex | Uso |
|---|---|---|
| bg | #0B0F14 | Fondo de la app, interior de los segmentados |
| surface | #11171E | Tarjetas y paneles |
| surface2 | #1A232D | Botones secundarios, cajas de ícono, miniaturas |
| surfaceActive | #18212B | Tile activo del dock |
| dialog | #131A22 | Diálogo |
| line | #1E2731 | Bordes de tarjetas y separadores |
| line2 | #26313D | Bordes de botones secundarios |
| line3 | #3A4654 | Bordes de botones con contorno |
| dashed | #2E3946 | Borde punteado de desconectado |
| text | #E8EDF2 | |
| text2 | #C9D2DC | |
| body | #A9B4C0 | Cuerpo del diálogo |
| muted | #8A97A6 | |
| faint | #5B6775 | |
| ecgBg | #0D1318 | Fondo del trazado |
| gridMajor | #1A242D | |
| gridMinor | #121A20 | |
| videoBg | #05080B | Fondo del visor |

**Parámetros**

| Parámetro | Hex |
|---|---|
| ECG / FC | #45D483 |
| SpO₂ | #3CC8E8 |
| Resp | #F2C94C |
| PA | #B9A6FF |
| Temp | #FF9F5A |
| Fonocardiograma | #8FB8FF |

**Estado**

| Token | Hex |
|---|---|
| primary | #4C9BFF (texto encima: #06101C) |
| alarm | #FF4D4F |
| alarmBg | #2A1013 |
| alarmText | #FF8A8A |
| warn | #FFB020 |
| ok | #45D483 |

**Tipografía**
- Barlow (400/500/600/700) para la UI.
- Barlow Semi Condensed (500/600) para valores numéricos, con `fontFeatureSettings = "tnum"`.
- Ambas son Google Fonts con licencia OFL: incluirlas en `res/font`.
- Escala en sp: 12 · 13 · 14 · 15 · 16 · 17 · 18 · 22 · 26 · 30 · 44 · 52 · 64 · 112.

**Espaciado, radios y tamaños**
- Espaciado en dp: 2 · 3 · 6 · 8 · 10 · 12 · 14 · 16 · 18 · 20 · 24 · 28.
- Radios en dp: 6 (tira) · 8 (segmento, miniatura) · 10 (botón chico, visor) · 11 (contenedor segmentado, caja de ícono) · 12 (botón grande) · 14 (tarjeta) · 15/13 (chips) · 18 (diálogo).
- Alturas de control en dp: 40 (segmento) · 44 · 46 · 48 · 52 · 68 (acción principal).
- Sin sombras (solo el diálogo con scrim). La profundidad sale de los fondos y bordes.

## Assets
- **Íconos:** Material Symbols Rounded (weight 400, sin relleno).
  - Nombres: `hearing`, `stethoscope`, `videocam`, `arrow_back`, `pause`, `play_arrow`, `save`, `close`, `power_settings_new`, `cameraswitch`, `mic`, `fiber_manual_record`, `remove`, `add`, `open_in_full`, `chevron_right`.
  - En Android: Material Icons Extended (`Icons.Rounded.*`) o importar los vectores desde fonts.google.com/icons.
- **Imágenes:** ninguna. Los visores de video son placeholders para los streams reales.

## Files
- `Panel Medico.dc.html`: prototipo hi-fi interactivo (fuente de verdad de la UI).
- `Panel Medico - Wireframes UX.dc.html`: wireframes UX, diagnóstico y alternativas descartadas.
- `support.js`: runtime necesario para abrir los `.dc.html` en el navegador.
- `uploads/pasted-1793276975144-0.png`: pantalla actual (antes).
- `screenshots/`: capturas de referencia de cada estado.
  - Al sacarlas, el prototipo se escaló para entrar en la ventana, así que las capturas no tienen resolución nativa. Las medidas exactas son las de este README, no las de las capturas.
  - Estados incluidos: `01` ECG por defecto · `02` ECG congelado · `03` PA midiendo · `04` temperatura conectada · `05` otoscopio en vivo · `06` otoscopio congelado · `07` captura guardada · `08` estetoscopio grabando · `09` cámara · `10` diálogo de finalizar.

## Pendiente de confirmar con producto
- Si el ECG entrega 12 derivaciones, agregar la opción `12` y una vista expandida a pantalla completa.
- Límites de alarma configurables por parámetro (en el prototipo, FC 50–120) y si hay alarma sonora.
- Dónde se guardan las capturas y grabaciones, y si se envían al panel remoto.
