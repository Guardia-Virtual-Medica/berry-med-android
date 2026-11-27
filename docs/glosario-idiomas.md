# Glosario de idiomas de Berry Med

Las traducciones al inglés y al portugués las redactó un modelo (plan 19, paso 5, 27 de noviembre de 2026).
**Los términos clínicos los tiene que aprobar una persona** (un médico que use la app en ese idioma) antes de usar Berry Med con pacientes en inglés o en portugués.

Inglés de Estados Unidos; portugués de Brasil, de «você». La clave es la entrada de `app/src/main/res/values*/strings.xml` donde aparece el término (hay más entradas con el mismo término; esta es la de referencia). **A confirmar** marca una elección entre dos opciones razonables.

## Vocabulario del producto

Una sola traducción para cada uno, la misma en toda la app.

| Español | Inglés | Portugués | Clave | Nota |
|---|---|---|---|---|
| Maletín de atención | Care kit | Maleta de atendimento | `home_title` | A confirmar: en inglés también podría ser «Care case» o «Medical kit». |
| tótem | totem | totem | `hint_totem` | A confirmar en inglés: es el nombre del puesto en OnDoctor (su `external_id`); «totem» no es habitual en Estados Unidos, pero se mantiene para que el técnico lo encuentre igual en la plataforma. |
| kiosco | kiosk | quiosque | `step_kiosk` | |
| plaqueta (el monitor PM6750) | board («PM6750 board») | placa («placa PM6750») | `main_chip_berry` | |
| Panel Local | Local Panel | Painel Local | `local_panel` | |
| teleconsulta | telehealth visit («Telehealth» como título) | teleconsulta | `testing_tele`, `home_ondoctor_body` | |
| consulta (en curso, enviando) | visit | consulta | `testing_status_session`, `language_blocked` | |
| hub (YKUSH3) | hub («YKUSH3 hub») | hub («Hub YKUSH3») | `testing_ykush` | |
| Inicio | Home | Início | `crumb_home` | |
| Configuración | Settings | Configurações | `home_config` | En portugués, «Ajustes de Android» también es «Configurações do Android». |
| auricular / auriculares | headset / headphones | fone / fones de ouvido | `status_headset`, `call_audio_headset` | En el panel lateral, «Fone» a secas para que entre en un renglón. |
| parlante / altavoz | speaker | alto-falante | `status_speaker` | |
| jack de la tablet | tablet jack | conector do tablet | `call_audio_headset` | En portugués «o tablet», masculino, como se usa en Brasil. |
| toma de signos | vitals take | coleta de sinais vitais | `rt_post_waiting_take` | A confirmar en inglés. |
| Órdenes (de Pusher) | Commands | Ordens | `step_orders` | |
| pista (de LiveKit) | track | trilha | `rt_tele_otoscope_rejected` | |
| Cortar (la prueba manual) | Hang up | Encerrar | `testing_tele_stop` | |
| Falló … (tareas de la teleconsulta) | … failed | Falha … | `rt_tele_failed_*` | Nombres internos de tarea pasados a algo que un técnico entienda: «Otoscope order failed», «Falha na ordem do otoscópio». |
| por UVC / por Camera2 | via UVC / via Camera2 | via UVC / via Camera2 | `testing_tele_path_uvc` | Entra en «Publishing: otoscope via UVC 800×800 · 30.0 fps». |

No se traducen: ONDOCTOR365, OnDoctor, Berry Med, YKUSH3, PM6750, LiveKit, Pusher, Testing, USB-config, CameraX, Camera2, UVC, Chrome, los nombres de los idiomas del selector (Español, English, Português) y las unidades (mmHg, °C, mm/s, mm/mV, SpO₂).

## Términos clínicos y abreviaturas

| Español | Inglés | Portugués | Clave | Nota |
|---|---|---|---|---|
| FC (frecuencia cardíaca) | HR | FC | `local_hr` | |
| lpm (latidos por minuto) | bpm | bpm | `local_unit_bpm` | |
| Resp (frecuencia respiratoria, rótulo) | Resp | Resp | `local_resp` | |
| FR (frecuencia respiratoria) | RR | FR | `testing_verdict_resp_ok` | |
| rpm (respiraciones por minuto) | brpm | irpm | `main_unit_rpm`, `local_unit_rpm` | A confirmar en los dos: «rpm» o «/min» también se usan. |
| PA (presión arterial) | BP | PA | `local_pa` | |
| Presión (tarjeta, fila del panel lateral) | Blood pressure / BP | Pressão / Pressão arterial | `label_nibp`, `status_device_pressure` | A confirmar: en el panel lateral en inglés quedó «BP». |
| Pulso | Pulse | Pulso | `local_pulse` | |
| SpO₂ / pulso | SpO₂ / pulse | SpO₂ / pulso | `label_spo2` | |
| derivaciones / deriv. | leads | derivações / deriv. | `local_leads`, `testing_ecg_detail_leads_on` | |
| electrodos sueltos | Leads off | Eletrodos soltos | `status_leads_off`, `local_leads_off` | En Testing, «electrodes: off» / «eletrodos: soltos». |
| Sin señal / Señal débil / Señal buena | No signal / Weak signal / Good signal | Sem sinal / Sinal fraco / Sinal bom | `local_signal_*` | |
| desde ECG (la FC) | from ECG | pelo ECG | `local_hr_source` | |
| Ganancia de ECG | ECG gain | Ganho do ECG | `label_ecg_gain` | |
| Modo de ECG: Operación / Monitor / Diagnóstico | Surgery / Monitor / Diagnostic | Cirurgia / Monitor / Diagnóstico | `main_ecg_mode_*` | A confirmar contra el manual del PM6750: «Operación» también podría ser «Operation» / «Operação». |
| Modo de presión: Adulto / Niño / Neonato | Adult / Pediatric / Neonatal | Adulto / Pediátrico / Neonatal | `main_nibp_mode_*` | A confirmar. |
| presión de inflado | inflation pressure | pressão de insuflação | `hint_nibp_target` | A confirmar en portugués («pressão de inflação» también se usa). |
| manguito | cuff | manguito | `testing_nibp_detail_idle` | |
| Inflando… / Midiendo… | Inflating… / Measuring… | Insuflando… / Medindo… | `local_pa_inflating`, `local_pa_deflating` | |
| termómetro IR / IR | IR thermometer / IR | termômetro infravermelho / IV | `testing_card_thermo`, `local_temp_source` | A confirmar en portugués: «IV» como abreviatura de infravermelho. |
| gatillo (del termómetro) | trigger | gatilho | `testing_thermo_trigger` | |
| Sin dedo en el sensor | No finger on the sensor | Sem dedo no sensor | `testing_verdict_no_finger` | |
| otoscopio | otoscope | otoscópio | `local_otoscope` | |
| estetoscopio | stethoscope | estetoscópio | `local_stetho` | |
| fonocardiograma | phonocardiogram | fonocardiograma | `local_phono_label` | |
| filtro Corazón / Pulmón / Amplio | Heart / Lung / Wide | Coração / Pulmão / Amplo | `local_stetho_heart`, `local_stetho_lung`, `local_stetho_wide` | |

## Las 12 arritmias

Se muestran en minúscula debajo de la FC del Panel Local. El código es el que manda la plaqueta y viaja a la API: no cambia con el idioma; sólo cambia lo que se lee. Las claves son `arrhythmia_<código>`.

| Código | Español | Inglés | Portugués | Nota |
|---|---|---|---|---|
| `normal` | normal | normal | normal | |
| `paro` | paro | asystole | assistolia | A confirmar: en inglés también «cardiac arrest». |
| `fibrilacion_ventricular` | fibrilación ventricular | ventricular fibrillation | fibrilação ventricular | |
| `R_sobre_T` | R sobre T | R on T | R sobre T | |
| `extrasistoles_ventriculares_multiples` | extrasístoles ventriculares múltiples | multiple PVCs | extrassístoles ventriculares múltiplas | A confirmar: «multiform PVCs» si la plaqueta se refiere a multifocales. |
| `extrasistoles_ventriculares_dobles` | extrasístoles ventriculares dobles | PVC couplet | extrassístoles ventriculares pareadas | A confirmar. |
| `extrasistole_ventricular` | extrasístole ventricular | PVC | extrassístole ventricular | |
| `bigeminismo` | bigeminismo | bigeminy | bigeminismo | |
| `trigeminismo` | trigeminismo | trigeminy | trigeminismo | |
| `taquicardia` | taquicardia | tachycardia | taquicardia | |
| `bradicardia` | bradicardia | bradycardia | bradicardia | |
| `latido_perdido` | latido perdido | missed beat | batimento perdido | A confirmar: en inglés también «dropped beat». |
