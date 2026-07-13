plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.weyland.nodepoc"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.weyland.tavern"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Real phones only. (x86_64 emulator support would need a second
            // runtime fetched from the Termux x86_64 repo — add later if wanted.)
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    packaging {
        jniLibs {
            // Extract our "libraries" (the Node executable + its deps) to
            // nativeLibraryDir at install time so ProcessBuilder can exec them.
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}