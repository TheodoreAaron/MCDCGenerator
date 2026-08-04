plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mcdc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.mcdc"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 固定发布密钥：仅当 CI 注入 RELEASE_KEYSTORE 环境变量时启用；
    // 本地/早期无密钥环境自动回退到默认 debug key，保证任何环境都能构建。
    val hasReleaseKey = !System.getenv("RELEASE_KEYSTORE").isNullOrBlank()

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
        if (hasReleaseKey) {
            create("releaseKey") {
                storeFile = file(System.getenv("RELEASE_KEYSTORE")!!)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("releaseKey")
                            else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            // 测试版也用固定密钥，避免不同 CI runner 各自生成 debug key 导致覆盖安装签名冲突
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("releaseKey")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    composeOptions {
        // Kotlin 1.9.x 使用独立的 Compose 编译器工件（非 kotlin.plugin.compose 插件）
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose BOM 统一管理 Compose 库版本
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
