plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wordflow.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wordflow.keyboard"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val storePath = System.getenv("WORDFLOW_KEYSTORE_PATH")
            val storePass = System.getenv("WORDFLOW_KEYSTORE_PASSWORD")
            val keyAliasValue = System.getenv("WORDFLOW_KEY_ALIAS")
            val keyPass = System.getenv("WORDFLOW_KEY_PASSWORD")
            if (!storePath.isNullOrBlank() && !storePass.isNullOrBlank() &&
                !keyAliasValue.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                signingConfig = signingConfigs.create("wordflowRelease") {
                    storeFile = file(storePath)
                    storePassword = storePass
                    keyAlias = keyAliasValue
                    keyPassword = keyPass
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
