import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
}

// Upload-key credentials live in the git-ignored keystore.properties at the project root (see
// docs/play-store/README.md). Without it, release builds are produced unsigned.
val uploadKey = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasUploadKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !uploadKey.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.dtn.mesh"
    // Google Play requires new apps and updates to target Android 16 (API 36) since 31 Aug 2026.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dtn.mesh"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
                storeFile = rootProject.file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasUploadKey) signingConfig = signingConfigs.getByName("upload")
        }
        // The release (R8-minified) code under a separate package, debug-signed, so it installs
        // NEXT TO the everyday debug app for on-device testing without touching its data.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true // VERSION_NAME / VERSION_CODE for Settings → About
    }

    // The MQTT client pulls in Netty, which ships duplicate META-INF resources.
    packaging {
        resources {
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.work)
    kapt(libs.hilt.work.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle / ViewModel
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.hilt.navigation.compose)

    // Serialization (for research export)
    implementation(libs.kotlinx.serialization.json)

    // Cryptography: X25519 / Ed25519 / HKDF (pure-Java, works on API 26+)
    implementation(libs.bouncycastle)

    // QR identity exchange
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    // Internet transport (MQTT relay)
    implementation(libs.hivemq.mqtt)

    // Settings persistence
    implementation(libs.datastore.preferences)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso)
}

kapt {
    correctErrorTypes = true
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}
