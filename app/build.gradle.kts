plugins {
    id("com.android.application")
}

android {
    namespace = "cn.xiangyu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.xiangyu.travelguide"
        minSdk = 26
        targetSdk = 35
        versionCode = 35
        versionName = "1.9.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
