plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.storysaver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.storysaver"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Solo para inyectar el hook JS antes de que cargue la página (document-start script).
    implementation("androidx.webkit:webkit:1.11.0")
}
