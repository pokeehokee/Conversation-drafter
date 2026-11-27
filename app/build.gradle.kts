// AGP 9 compiles Kotlin itself, so there is no separate kotlin-android plugin.
plugins {
    id("com.android.application")
}

// One fixed signing key, so each new APK installs over the last one.
// CI decodes it from the repo's Actions secrets; without them the build falls back
// to Android's throwaway debug key (fine for testing, but it won't update in place).
val signingKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.let(::file)?.takeIf { it.isFile }
val signingPassword = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.devin.replydrafter"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.devin.replydrafter"
        minSdk = 30 // Android 11: needed for takeScreenshot()
        targetSdk = 37
        // Each CI run gets a higher versionCode, so Android accepts it as an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (signingKeystore != null && signingPassword != null) {
            create("shared") {
                storeFile = signingKeystore
                storePassword = signingPassword
                keyAlias = "replydrafter"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
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
