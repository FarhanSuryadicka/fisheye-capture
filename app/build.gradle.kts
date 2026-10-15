import java.util.Properties

plugins {
    id("com.android.application")
}

// Kunci rilis: keystore/fisheye-release.jks + password di keystore.properties
// (keduanya tidak boleh di-commit). Simpan cadangannya: tanpa kunci yang sama,
// APK versi baru tidak bisa dipasang menimpa versi lama.
val releaseProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "id.aranus.fisheyecapture"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.aranus.fisheyecapture"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    ndkVersion = "27.1.12297006"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(releaseProps.getProperty("storeFile", "keystore/fisheye-release.jks"))
            storePassword = releaseProps.getProperty("storePassword")
            keyAlias = releaseProps.getProperty("keyAlias")
            keyPassword = releaseProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8 off: nama fungsi JNI (UvcNative) harus tetap utuh.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
