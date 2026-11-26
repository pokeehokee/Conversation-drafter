// AGP 9 compiles Kotlin itself, so there is no separate kotlin-android plugin.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.devin.replydrafter"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.devin.replydrafter"
        minSdk = 30 // Android 11: needed for takeScreenshot()
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
