plugins {
    id("com.android.application")
}

android {
    namespace = "cn.xiangyu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.xiangyu.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "1.8.1"
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
