plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tripguard.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.tripguard.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Centralised, configurable backend base URL. Override per build with:
        //   ./gradlew assembleDebug -PapiBaseUrl="http://192.168.1.20:8000/"
        // The default points at the Android emulator host loopback. For a physical device
        // on a hotspot, pass the reachable host IP through the property above. The value is
        // also user-overridable at runtime from Settings.
        // providers.gradleProperty keeps this as a tracked input so Gradle's configuration
        // cache regenerates BuildConfig when the property changes (findProperty did not).
        val apiBaseUrl = providers.gradleProperty("apiBaseUrl").getOrElse("http://10.0.2.2:8000/")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Google ML Kit Text Recognition v2 with the bundled Latin-script model. The model is
    // packaged into the APK so the first OCR run does not require a download. Recovers
    // ML Kit and its transitives offline from the Gradle cache.
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
