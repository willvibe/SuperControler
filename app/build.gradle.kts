plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.yourapp.supercontroler"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yourapp.supercontroler"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release-key.jks")
            storePassword = "release123"
            keyAlias = "release"
            keyPassword = "release123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "SIGNAL_SERVER", "\"wss://101.33.80.14:8765/ws\"")
        }
        debug {
            buildConfigField("String", "SIGNAL_SERVER", "\"wss://101.33.80.14:8765/ws\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:webrtc"))
    implementation(project(":core:root"))
    implementation(project(":core:crypto"))
    implementation(project(":feature:controlled"))
    implementation(project(":feature:controller"))

    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.webrtc)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("com.google.android.material:material:1.11.0")

    debugImplementation(libs.compose.ui.tooling)
}
