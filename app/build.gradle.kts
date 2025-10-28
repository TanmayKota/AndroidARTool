plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.ar_google_maps_app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.ar_google_maps_app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // -------------------------
    // AR / Geospatial & location
    // -------------------------
    // ARCore runtime (required on devices) - keep this
    implementation("com.google.ar:core:1.37.0")

    // NOTE: Removed `com.google.ar:arcore-extensions` dependency.
    // The `arcore-extensions` artifact you had (1.36.0) is not a published native Android
    // Maven artifact and causes resolution errors. For native Android Geospatial features
    // use the ARCore runtime (`com.google.ar:core`) and the ARCore Geospatial APIs from
    // the ARCore SDK / documentation. If you later need Unity/AR Foundation support,
    // the Unity package is distributed separately.

    // Google Play Services Location (useful for fine-grained location fallbacks / checks)
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // -------------------------
    // Networking & JSON parsing
    // -------------------------
    // OkHttp (HTTP client)
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // Retrofit for easy REST calls (Directions API etc.)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")

    // Moshi JSON library (used by Retrofit converter)
    implementation("com.squareup.moshi:moshi:1.15.0")

    // -------------------------
    // Optional: (comment/uncomment as needed)
    // Scene/3D rendering helper or Filament if you want easier 3D model rendering
    // Filament (if you plan to use it directly) or Sceneform forks — not required for MVP
    // implementation("com.google.android.filament:filament-android:1.20.0")
    // implementation("com.gorisse.thomas.sceneform:core:1.16.0") // community forks only
}
