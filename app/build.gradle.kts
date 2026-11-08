plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("Firebase configuration is missing. This APK can open, but cannot register or receive messages.")
}

android {
    namespace = "org.miquelmartin.blinky"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.miquelmartin.blinky"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-messaging")
}
