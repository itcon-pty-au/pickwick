// Share the app's version catalog so the AGP API we compile against can't drift
// from the AGP the app is actually built with.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
