plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // HTTP via java.net.http (JDK built-in) — no extra dependency.
}

kotlin {
    jvmToolchain(21)
}
