plugins {
    id("com.android.application") version "9.1.1"
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

android {
    namespace = "ru.documentvault.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.documentvault.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}
