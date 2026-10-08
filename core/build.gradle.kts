plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
