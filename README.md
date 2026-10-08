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

## Compilar el APK

Hace falta **JDK 17** y el **Android SDK** (API 35). Lo más simple es instalar [Android Studio](https://developer.android.com/studio): trae el JDK y el SDK.

### Desde Android Studio

1. Clonar el repo y abrirlo con **File → Open**.
2. Esperar a que Gradle sincronice. Si pide instalar el SDK o el build-tools, aceptarlo.
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

El APK de debug queda en:

```
app/build/outputs/apk/debug/app-debug.apk
```

### Desde la terminal

Con Android Studio ya instalado, el SDK suele estar en `~/Library/Android/sdk` (macOS). Si Gradle no lo encuentra, crear `local.properties` en la raíz del repo (ese archivo no se commitea):

```properties
sdk.dir=/Users/TU_USUARIO/Library/Android/sdk
```

En Linux o Windows, `sdk.dir` apunta a la carpeta del SDK de esa máquina. También sirve exportar `ANDROID_HOME` con esa misma ruta.

Compilar:

```bash
./gradlew :app:assembleDebug
```

Instalarlo en la tablet conectada por USB (con depuración USB activada):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Los tests del parser no necesitan el SDK de Android:

```bash
./gradlew :core:test
```

## Licencia

GPLv3 (derivado del protocolo/parser de berry_med).
