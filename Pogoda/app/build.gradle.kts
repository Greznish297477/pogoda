plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
android {
    namespace = "com.greznish.pogoda"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.greznish.pogoda"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
