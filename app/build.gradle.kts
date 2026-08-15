import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. PICKWICK_KEYSTORE (local.properties or the environment)
// must point at the real release keystore; release builds fail without it.
// There is deliberately no debug-key fallback: the release key is the sole
// trust anchor for self-update, and Android refuses an in-place upgrade across
// a signature change, so a debug-signed release quietly published would strand
// every install. Fail the build instead of shipping the wrong signature.
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
        versionCode = 24
        versionName = "0.7.4-beta"

        // Self-update manifest: JSON with versionCode/versionName/apkUrl.
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://raw.githubusercontent.com/itcon-pty-au/pickwick/main/version.json\""
        )

        // Community channel directory — same JSON the pickwick.tv browse page
        // renders. Read from the repo via raw CDN, like the site's LIVE_DIR:
        // deploy-pages.yml skips site/directory/** on purpose, so the Pages
        // copy at pickwick.tv/directory only refreshes when an unrelated site
        // change happens to deploy — a merged suggestion could sit invisible
        // for weeks there. Raw updates within ~5 minutes of a merge.
        buildConfigField(
            "String",
            "DIRECTORY_URL",
            "\"https://raw.githubusercontent.com/itcon-pty-au/pickwick/main/site/directory/\""
        )

        // Mail-slot worker the website's suggestion form posts to. The app uses
        // its bulk route to offer a whole curated list for review at once.
        buildConfigField(
            "String",
            "SUGGEST_WORKER_URL",
            "\"https://pickwick-suggest.pickwick.workers.dev/\""
        )

        // Crawl-cursor trust stamp: a persisted NewPipe Page is only readable
        // by the extractor version that wrote it, and the extractor only
        // changes when this dependency does — sourced from the catalog so the
        // stamp can't drift from the actual library.
        buildConfigField(
            "String",
            "EXTRACTOR_VERSION",
            "\"${libs.versions.newpipeextractor.get()}\""
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
            signingConfig = signingConfigs.findByName("release")
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

// Fail release *packaging* — not configuration, so CI's assembleDebug on a
// keyless runner still works — when the real key is absent. Without this the
// APK would come out unsigned and uninstallable, discovered only on-device.
gradle.taskGraph.whenReady {
    // Exact names only (no flavors): a trailing wildcard would also match
    // library-style tasks like bundleReleaseClassesToRuntimeJar, which sit in
    // the plain `gradlew test` graph and would break tests on keyless machines.
    val wantsRelease = allTasks.any {
        it.project == project && it.name.matches(Regex("(assemble|package|bundle|install)Release"))
    }
    if (wantsRelease && releaseKeystore == null) {
        error(
            "Release builds need the real signing key: set PICKWICK_KEYSTORE " +
                "(plus _PASSWORD, PICKWICK_KEY_ALIAS, PICKWICK_KEY_PASSWORD) in " +
                "local.properties or the environment."
        )
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
    // Listen mode: MediaSession for lock-screen/headset controls while the
    // phone plays with its screen off.
    implementation(libs.media3.session)
    implementation(libs.androidx.media)
    implementation(libs.newpipeextractor)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime)
    implementation(libs.zxing.core)
    implementation(libs.coil.compose)
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests — the android.jar stubs throw "not mocked".
    testImplementation("org.json:json:20240303")
}
