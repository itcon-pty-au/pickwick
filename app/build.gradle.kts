plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.pickwick.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.pickwick.app"
        minSdk = 26 // adaptive icons; every realistic target device is far above this
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"

        // Self-update manifest: JSON with versionCode/versionName/apkUrl.
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://raw.githubusercontent.com/itcon-pty-au/pickwick/main/version.json\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-key signing so local perf/family installs can side-load the
            // optimized build; swap in a real keystore for wider distribution.
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.newpipeextractor)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.androidx.biometric)
    implementation(libs.zxing.core)
    implementation(libs.coil.compose)
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests — the android.jar stubs throw "not mocked".
    testImplementation("org.json:json:20240303")
}
