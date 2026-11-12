import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.android.application")
}

// Use an isolated javac process to avoid Windows/JDK archive-lock errors when
// the generated R.jar is closed after compilation.
tasks.withType<JavaCompile>().configureEach {
    options.isFork = true
}

val verifyCityContentData by tasks.registering {
    val contentFile = layout.projectDirectory.file("src/main/java/cn/xiangyu/app/CityContentData.java")
    inputs.file(contentFile)
    doLast {
        val content = contentFile.asFile.readText()
        val rejectedMarkers = listOf("\"{}", "生殖器", "费尔班克斯", "州府\\t州和府")
        rejectedMarkers.forEach { marker ->
            check(!content.contains(marker)) {
                "CityContentData contains rejected marker: $marker"
            }
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifyCityContentData)
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
        applicationId = "cn.xiangyu.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 40
        versionName = "1.9.6"
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
