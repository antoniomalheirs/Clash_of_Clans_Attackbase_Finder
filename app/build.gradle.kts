plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.debug.open_clans"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.debug.open_clans"
        minSdk = 28
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
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.localbroadcastmanager)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // CameraX — necessário para fix de 16KB alignment das libs nativas do ML Kit
    implementation("androidx.camera:camera-core:1.4.2")
}