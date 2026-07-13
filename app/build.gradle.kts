import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing lives in keystore.properties (gitignored, never
// committed). Debug builds work with no setup; release builds need this
// file present locally — see keystore.properties.example.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
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

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            optimization {
                // R8 minification stays off: the WeyTavBridge keep rule
                // covers the WebView interface, but there's no size/perf
                // pressure here to justify the added build complexity, and
                // this app is public source anyway — nothing to obfuscate.
                enable = false
            }
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
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