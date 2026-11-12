plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versioni da CI/release (vedi .github/workflows/*.yml); fallback per build locali.
val envVersionCode = System.getenv("HERMES_VERSION_CODE")?.toIntOrNull()
val envVersionName = System.getenv("HERMES_VERSION_NAME")
// Firma stabile condivisa da debug+release: l'updater in-app richiede che
// l'APK scaricato condivida il firmatario con quello installato. Senza env
// (build locali) si usa la debug-key di default, invariato.
val stableStoreFile = System.getenv("HERMES_KEYSTORE_PATH")
val stableStorePassword = System.getenv("HERMES_KEYSTORE_PASSWORD")
val stableKeyAlias = System.getenv("HERMES_KEY_ALIAS")
val stableKeyPassword = System.getenv("HERMES_KEY_PASSWORD")
val hasStableSigning = !stableStoreFile.isNullOrBlank() &&
    !stableStorePassword.isNullOrBlank() &&
    !stableKeyAlias.isNullOrBlank() &&
    !stableKeyPassword.isNullOrBlank()

android {
    namespace = "com.hermes.studio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hermes.studio"
        minSdk = 26
        targetSdk = 35
        versionCode = envVersionCode ?: 2
        versionName = envVersionName ?: "0.1.1"
    }
    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = file(stableStoreFile!!)
                storePassword = stableStorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    // Player video in-app (niente player esterni): ExoPlayer + controller nativo.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    // Update in-app classico (stile UniNote/Hub): WorkManager + notifiche
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
