plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.foliodock"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "app.foliodock"
        minSdk = 26
        targetSdk = 35
        versionCode = 200000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "2.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("release") {
            val path = System.getenv("FOLIODOCK_STORE_FILE")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("FOLIODOCK_STORE_PASSWORD")
                keyAlias = "foliodock"
                keyPassword = System.getenv("FOLIODOCK_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    sourceSets.getByName("main").assets.srcDir("src/main/office-assets")
    androidResources {
        // LibreOffice's APK virtual filesystem memory-maps STORE entries, not deflated assets.
        noCompress += ""
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Upstream F-Droid libraries are already stripped. Keep them byte-identical.
            keepDebugSymbols += "**/*.so"
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}
