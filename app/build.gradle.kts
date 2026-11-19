plugins {
    id("com.android.application")
}

android {
    namespace = "com.ibt.translate"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.ibt.translate"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
