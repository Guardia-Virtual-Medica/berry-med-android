plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.guardiavirtual.berrymed"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.guardiavirtual.berrymed"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Sólo ARM: la tablet es ARM, el emulador berry_tablet también (Mac con Apple Silicon),
        // y libuvc (el otoscopio por UVC) no trae x86. WebRTC de LiveKit trae las cuatro
        // arquitecturas y, sin este filtro, el APK pasaba de 12 a 70 MB.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        // Las librerías nativas van comprimidas en el APK (Android las descomprime al instalar):
        // sin comprimir, WebRTC solo sumaba 19 MB al APK que se baja de Releases.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.pusher:pusher-java-client:2.4.4")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // La cámara de la tablet en el Panel Local.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // El otoscopio cuando la tablet no lo lista como cámara externa: UVC directo por USB.
    // Es el módulo libuvc de AndroidUSBCamera (AUSBC). La 3.3.3 no compila en JitPack, y de
    // la 3.2.7 (la última que sí) se toma sólo este módulo: el resto trae Glide, MMKV, RTMP y
    // dos librerías que ya no se pueden bajar, y su registro USB rompe en Android 14.
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.7")
    // La teleconsulta: Berry Med publica el otoscopio y el estetoscopio en la sala de LiveKit
    // del encuentro. 2.29.0 es la última 2.x con más de dos semanas publicada (20 sep 2026).
    implementation("io.livekit:livekit-android:2.29.0")
    // El cuerpo de los eventos de Pusher y la respuesta del token. Ya viene con Pusher
    // (en tiempo de ejecución); se declara para compilar contra ella.
    implementation("com.google.code.gson:gson:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.21")
    // El envío contra un servidor de mentira; la misma versión que OkHttp.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
