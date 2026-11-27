plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.pushtv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.pushtv"
        minSdk = 28
        targetSdk = 34
        versionCode = 11
        versionName = "1.7.2"
    }

    flavorDimensions += "abi"
    productFlavors {
        create("universal") {
            dimension = "abi"
            // Debug-only variant used to exercise the Legacy code path on x86 API 23 emulators.
            minSdk = 23
        }
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("arm32") {
            dimension = "abi"
            ndk { abiFilters += "armeabi-v7a" }
        }
        create("legacyArm32") {
            dimension = "abi"
            minSdk = 23
            versionNameSuffix = "-legacy"
            ndk { abiFilters += "armeabi-v7a" }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/native-image/**"
            excludes += "**/*.dll"
            excludes += "**/*.dylib"
            excludes += "**/*.jnilib"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    
    // Coil
    implementation(libs.coil.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    
    // TV Compose
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    // ZXing for QR
    implementation(libs.zxing.embedded)

    testImplementation(libs.junit)
}

// 自定义生成的 APK 文件名
androidComponents {
    beforeVariants { variantBuilder ->
        val abi = variantBuilder.productFlavors.firstOrNull { it.first == "abi" }?.second
        if (variantBuilder.buildType == "debug" && abi != "universal") {
            variantBuilder.enable = false
        }
        if (variantBuilder.buildType == "release" && abi == "universal") {
            variantBuilder.enable = false
        }
    }
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val appName = "PushTV"
            val versionName = variant.outputs.firstOrNull()?.versionName?.get() ?: "1.0"
            val abi = variant.productFlavors.firstOrNull { it.first == "abi" }?.second
            val suffix = when {
                variant.buildType == "release" && abi == "arm64" -> "release_arm64-v8a"
                variant.buildType == "release" && abi == "arm32" -> "release_armeabi-v7a"
                variant.buildType == "release" && abi == "legacyArm32" -> "release_armeabi-v7a"
                else -> variant.buildType
            }
            output.outputFileName.set("${appName}_v${versionName}_${suffix}.apk")
        }
    }
}
