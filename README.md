# berry-med-android

App Android para tablet tótem: reemplaza `berry-monitor.exe` leyendo la plaqueta BerryMed PM6750 por USB, opcionalmente termómetro IR y relé USBRelay2, y publicando métricas al mismo endpoint que la app web.

## Requisitos

- Tablet Android 8+ (API 26+) con USB host (OTG)
- Hub USB alimentado si conectás plaqueta + termómetro + relé a la vez
- Misma configuración que el `.exe`: URL base, Totem ID, credenciales API, Pusher (canal **público**)

## Flujo

1. La app abre los dispositivos USB (serie CDC para Berry/termómetro, HID `16C0:05DF` para el relé).
2. Parsea tramas `55 AA` y arma el payload JSON compatible con [berry_med](https://github.com/Abrunacci/berry_med).
3. Escucha Pusher: `start-monitoring`, `stop-monitoring`, `start-blood-pressure`.
4. Con sesión activa, `POST` cada 1 s a `{API_URL}/{TOTEM_ID}/metrics` con Basic Auth.

## Build

Abrir en Android Studio o:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Licencia

GPLv3 (derivado del protocolo/parser de berry_med).
