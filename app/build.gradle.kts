plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yeop.seatcatcher"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.yeop.seatcatcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // 빌드할 때마다 같은 키로 서명 → 덮어서 설치 가능 (개인용 디버그 키)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug-fixed.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 화면 글자 인식 (한국어, 기기 안에서 처리)
    implementation("com.google.mlkit:text-recognition-korean:16.0.0")
}
