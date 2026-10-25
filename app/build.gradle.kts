plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.klang"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.klang.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "1.0.0"
    }

    // The release key never lives in the repository. CI writes it from GitHub Secrets
    // (KLANG_KEYSTORE_B64, KLANG_KEYSTORE_PASSWORD, KLANG_KEY_ALIAS); without them the
    // release build comes out unsigned.
    val ksFile = file("release.jks")
    val ksPass = System.getenv("KLANG_KEYSTORE_PASSWORD")
    val ksAlias = System.getenv("KLANG_KEY_ALIAS")
    val canSign = ksFile.exists() && !ksPass.isNullOrEmpty() && !ksAlias.isNullOrEmpty()
    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (canSign) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")

    // YouTube search and stream extraction (GPL-3.0), the same library Droidamp uses
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.2")
}
