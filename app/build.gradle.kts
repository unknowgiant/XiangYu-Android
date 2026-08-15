import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val amapWebKey = localProperties.getProperty("AMAP_WEB_KEY", "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "cn.xiangyu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.xiangyu.travelguide"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "1.9.3"
        buildConfigField("String", "AMAP_WEB_KEY", "\"$amapWebKey\"")
    }

    buildFeatures {
        buildConfig = true
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
