plugins {
    id("com.android.application") version "8.7.2"
    kotlin("android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "xyz.jmc.gozar"

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.jmc.gozar"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
