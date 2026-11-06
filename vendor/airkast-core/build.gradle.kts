import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * airkast-core, vendored from https://github.com/aivanyuk/airkast (Apache-2.0, see LICENSE/NOTICE),
 * with one local patch: the base SETUP announces `"PTP"` timing when NTP is off, because an Apple
 * TV answers `"None"` with 500 and stalls ~32 s before answering `"NTP"` with 400. See README.md.
 *
 * Only the `core` artifact is vendored: it is plain Kotlin/JVM with no Android dependency. The
 * app already discovers AirPlay receivers and keeps its own settings, so `airkast-android`'s
 * discovery and network binding are not needed.
 */
plugins {
    // Version is the build's: the Kotlin plugin is already on the classpath through the root build.
    kotlin("jvm")
}

kotlin {
    // No `jvmToolchain`: the app targets JVM 11 on whatever JDK runs Gradle (21 on the
    // build box), and asking for a toolchain here needs a JDK 17 that machine does not
    // have. Bytecode is the same either way.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
