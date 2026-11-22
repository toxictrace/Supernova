import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystoreProperties = Properties().apply {
    rootProject.file("local/keystore.properties")
        .takeIf { it.exists() }
        ?.inputStream()?.use(::load)
}

android {
    namespace = "com.supernova.googlecompanion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.supernova.googlecompanion"
        minSdk = 28
        // Google App accepts the bridge as a debuggable launcher client.
        // Keep the legacy target for the private launcher-overlay protocol.
        targetSdk = 28
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            // Google App's launcher overlay accepts system and debug clients.
            isDebuggable = true
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
