import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. Point PICKWICK_KEYSTORE (local.properties or the
// environment) at a real keystore and release builds use it; leave it unset and
// they fall back to the debug key, exactly as before.
//
// The fallback is not laziness — the debug key is what every installed family's
// copy is signed with, and Android refuses an in-place upgrade across a
// signature change. Switching keys therefore means an uninstall (which wipes
// their curation) and has to stay a deliberate, announced act. Until then,
// treat ~/.android/debug.keystore as a release credential: it is the sole trust
// anchor for self-update.
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingProp(name: String): String? =
    (signingProps.getProperty(name) ?: System.getenv(name))?.takeIf { it.isNotBlank() }

val releaseKeystore: String? = signingProp("PICKWICK_KEYSTORE")

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

        // Community channel directory — same JSON the pickwick.tv browse page
        // renders. Served via Pages (not raw.githubusercontent) so app and site
        // share one canonical URL.
        buildConfigField(
            "String",
            "DIRECTORY_URL",
            "\"https://pickwick.tv/directory/\""
        )
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingProp("PICKWICK_KEYSTORE_PASSWORD")
                keyAlias = signingProp("PICKWICK_KEY_ALIAS")
                keyPassword = signingProp("PICKWICK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig =
                signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    // Parents see this filename in download bars and release assets. Kept
    // constant (no version suffix) so releases/latest/download/pickwick.apk
    // never goes stale — the version lives in the release tag.
    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                    .outputFileName = "pickwick.apk"
            }
        }
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
    implementation(libs.androidx.security.crypto)
    implementation(libs.zxing.core)
    implementation(libs.coil.compose)
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests — the android.jar stubs throw "not mocked".
    testImplementation("org.json:json:20240303")
}
