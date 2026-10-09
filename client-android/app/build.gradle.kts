// Android client - build.gradle.kts (module)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.cloudgamehub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cloudgamehub"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "1.3.0"

        // Inject default server URL via build-time variable if desired
        // buildConfigField("String", "DEFAULT_SERVER", "\"http://192.168.1.100:14322\"")
    }

    signingConfigs {
        create("release") {
            // Keystore 在 client-android/keys/cloudgamehub-release.p12
            // 2026-10 重新生成过一次 (项目从 NasGameHub 改名过来), 指纹见 keys/FINGERPRINT.txt
            storeFile = file("../keys/cloudgamehub-release.p12")
            storePassword = "cloudgamehub2026"
            keyAlias = "cloudgamehub"
            keyPassword = "cloudgamehub2026"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Sign debug builds with release key so installation is consistent across upgrades.
            // (Without this, debug + release keys would conflict; user would have to uninstall first.)
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    // Coil (本地封面加载)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // HTTP: 115 webapi / libretro 缩略图 / RA 核心下载
    // (没有 Retrofit 了 —— 不再有服务器, 全是裸 OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 115 扫码登录的二维码生成
    implementation("com.google.zxing:core:3.5.3")

    // DataStore (设置)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
