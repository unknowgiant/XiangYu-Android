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
        versionCode = 23
        versionName = "1.7.0"
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
