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
        versionCode = 29
        versionName = "1.8.5"
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
