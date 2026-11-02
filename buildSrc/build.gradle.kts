plugins {
    `kotlin-dsl`
}

dependencies {
    // `implementation`, not compileOnly: build scripts load buildSrc in a parent
    // classloader that can't see the Android plugin's own classes, so the
    // instrumentation factory needs AGP's API (and ASM) on its own classpath.
    // The version comes from the shared catalog, so it matches the app's AGP.
    implementation("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
    implementation("org.ow2.asm:asm:9.6")
}
