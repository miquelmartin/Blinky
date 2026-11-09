plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("Firebase configuration is missing. This APK can open, but cannot register or receive messages.")
}

val releaseStoreFile = providers.environmentVariable("APK_SIGNING_STORE_FILE").orNull
val releasePassword = providers.environmentVariable("APK_KEY_PASSWORD").orNull
val releaseAlias = providers.environmentVariable("APK_KEY_ALIAS").orNull
val releaseSigningAvailable = !releaseStoreFile.isNullOrBlank() &&
    !releasePassword.isNullOrBlank() && !releaseAlias.isNullOrBlank()

android {
    namespace = "org.miquelmartin.blinky"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.miquelmartin.blinky"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storeType = "PKCS12"
                storePassword = releasePassword
                keyPassword = releasePassword
                keyAlias = releaseAlias
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val validateReleaseConfiguration by tasks.registering {
    doLast {
        check(file("google-services.json").isFile) {
            "Release packaging requires app/google-services.json from the registered org.miquelmartin.blinky Firebase app."
        }
        check(releaseSigningAvailable) {
            "Release packaging requires APK_SIGNING_STORE_FILE, APK_KEY_PASSWORD, and APK_KEY_ALIAS. No unsigned or debug-signed release fallback is allowed."
        }
        check(file(requireNotNull(releaseStoreFile)).isFile) {
            "The PKCS12 release keystore file does not exist."
        }
    }
}

tasks.configureEach {
    if (name in setOf("assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle", "signReleaseBundle")) {
        dependsOn(validateReleaseConfiguration)
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
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
