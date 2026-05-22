plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    id("com.chaquo.python")
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        // Optional remote host for setup-time asset download.
        buildConfigField("String", "ASSET_BASE_URL", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Keep native libs compressed for better compatibility on devices with
            // 16 KB page sizes when using older toolchains/native wheels.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.10"

        // Prefer an explicit property, but fall back to common local Python installs so
        // Android Studio "Run" builds still bundle the required packages by default.
        val requestedBuildPython = project.findProperty("chaquopy.buildPython") as String?
        val candidateBuildPython = listOfNotNull(
            requestedBuildPython,
            System.getenv("CHAQUOPY_BUILD_PYTHON"),
            "C:\\Users\\AHMADD~1\\AppData\\Local\\Programs\\Python\\PYTHON~2\\python.exe",
            "${System.getenv("LOCALAPPDATA")}\\Programs\\Python\\Python310\\python.exe",
            "${System.getenv("LOCALAPPDATA")}\\Programs\\Python\\Python311\\python.exe",
            "${System.getenv("LOCALAPPDATA")}\\Programs\\Python\\Python313\\python.exe",
            "${System.getenv("USERPROFILE")}\\AppData\\Local\\Programs\\Python\\Python310\\python.exe",
            "${System.getenv("USERPROFILE")}\\AppData\\Local\\Programs\\Python\\Python311\\python.exe",
            "${System.getenv("USERPROFILE")}\\AppData\\Local\\Programs\\Python\\Python313\\python.exe"
        ).firstOrNull { it.isNotBlank() && file(it).exists() }

        if (!candidateBuildPython.isNullOrBlank()) {
            buildPython(candidateBuildPython)
        }

        val enablePip = (project.findProperty("enableChaquopyPip") as String?)?.toBoolean() ?: true
        if (enablePip) {
            pip {
                install("numpy")
                install("../vendor/numba_stub")
                install("librosa==0.8.1")
                install("resampy==0.4.3")
                install("matplotlib")
                install("pandas")
            }
        }

        pyc {
            src = false
        }
    }
}

dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.16.1")
    implementation("com.airbnb.android:lottie:6.5.2")
    implementation("com.jjoe64:graphview:4.2.2")
    implementation("com.github.dragoon000320:tarsosdsp:1.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.7.0")

    // AndroidX Dependencies
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.5.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
    implementation("androidx.fragment:fragment-ktx:1.5.5")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.3")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}
