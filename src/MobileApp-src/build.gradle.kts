plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sacram.proxy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sacram.proxy"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        // Overridable from CI via -PappVersion so the keep-alive heartbeat reports
        // the exact release tag the build is published under.
        val appVersion = (project.findProperty("appVersion") as? String) ?: "1.41"
        versionName = appVersion
    }

    signingConfigs {
        // Fixed release key so every CI build shares one signature and updates
        // install in place (no more "package conflicts with an existing package").
        // Keystore is supplied via CI secrets; locally the env vars are absent
        // and the default debug key is used instead, which is fine for dev.
        create("ci") {
            val b64 = System.getenv("SACRAM_KEYSTORE_BASE64")
            if (b64 != null) {
                val keyFile = File(project.rootDir, "sacram-release-key.jks")
                if (!keyFile.exists()) {
                    keyFile.writeBytes(java.util.Base64.getDecoder().decode(b64))
                }
                storeFile = keyFile
                storePassword = System.getenv("SACRAM_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("SACRAM_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("SACRAM_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("SACRAM_KEYSTORE_BASE64") != null) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
