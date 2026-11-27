plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.psurvival.worker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.psurvival.worker"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
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
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
