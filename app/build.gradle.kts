import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.gpic.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gpic.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "1.3.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = localProperties.getProperty("keystore.path")
            if (ksPath != null) {
                storeFile = rootProject.file(ksPath)
                storePassword = localProperties.getProperty("keystore.password", "")
                keyAlias = localProperties.getProperty("keystore.alias", "")
                keyPassword = localProperties.getProperty("keystore.password", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (localProperties.getProperty("keystore.path") != null) signingConfigs.getByName("release") else null
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.protobuf.java)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// Version-numbered APK filenames for release uploads (streambox convention:
// `make publish V=<versionName> CODE=<versionCode>` keeps these in sync).
val releaseVersion = "1.3.6"
val versionApks by tasks.registering(Copy::class) {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("*.apk")
    into(layout.buildDirectory.dir("outputs/apk/versioned"))
    rename { name ->
        name
            .replace("app-arm64-v8a-release.apk", "GPic-$releaseVersion-arm64-v8a.apk")
            .replace("app-armeabi-v7a-release.apk", "GPic-$releaseVersion-armeabi-v7a.apk")
            .replace("app-x86_64-release.apk", "GPic-$releaseVersion-x86_64.apk")
            .replace("app-x86-release.apk", "GPic-$releaseVersion-x86.apk")
            .replace("app-universal-release.apk", "GPic-$releaseVersion-universal.apk")
    }
}
