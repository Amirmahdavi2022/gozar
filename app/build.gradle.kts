plugins {
    id("com.android.application") version "8.7.2"
    kotlin("android") version "2.2.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

android {
    namespace = "xyz.jmc.gozar"

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.jmc.gozar"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // hev-socks5-tunnel ships an ndk-build makefile, so AGP drives ndk-build
    // rather than CMake here.
    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            // Signed with the same key as the debug builds, deliberately. Android refuses an
            // update signed by a different key, so switching keys here would mean every existing
            // installation has to be removed first — and removing it wipes the endpoint pool and
            // the scoreboard the app spent a connect earning.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // One key, committed, used by every build.
    //
    // Android refuses to install an update signed by a different key than the copy already on the
    // phone, and a debug build signs with a keystore the build machine generates on the spot — so
    // every ci build came out with a different signature and every install meant uninstalling
    // first, which also wipes the endpoint pool and the scoreboard the app spent a connect
    // learning. There is nothing to protect here: this key signs a debug build that anyone can
    // rebuild from this repo. The release key, when there is one, will not live in git.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/gozar.jks")
            storePassword = "gozarsign"
            keyAlias = "gozar"
            keyPassword = "gozarsign"
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

        // The proxy core and the shaping proxy are programs, not libraries, and they are started
        // with ProcessBuilder rather than loaded. That only works if the installer has unpacked
        // them onto disk, which is exactly what legacy packaging means. Left on the modern path
        // they stay compressed inside the apk, nativeLibraryDir holds nothing to execute, and the
        // engine fails with a file-not-found on a file that is plainly in the build.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")

    // Lyrebird (obfs4, meek_lite, webtunnel), Snowflake and DNSTT, wrapped for
    // mobile by the Tor community. BSD licensed, so nothing here has to change
    // licence to use it.
    implementation("com.netzarchitekten:IPtProxy:5.5.1")

    // Tor itself, as a native library rather than a gomobile one, so it does
    // not clash with IPtProxy. Also BSD licensed.
    implementation("info.guardianproject:tor-android:0.4.8.21.1")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
}
