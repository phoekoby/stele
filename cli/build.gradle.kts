plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow") version "8.3.5"
}

dependencies {
    implementation(project(":core"))
    implementation(project(":extractors"))
    implementation(project(":connectors"))
    implementation(project(":resolver"))
    implementation(project(":mcp"))
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") // merge into .mcp.json
}

kotlin {
    jvmToolchain(21)
}

application {
    applicationName = "stele"
    mainClass.set("dev.stele.cli.MainKt")
}

// Single runnable artifact: build/libs/stele.jar  (`java -jar stele.jar …`).
tasks.shadowJar {
    archiveBaseName.set("stele")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles() // Liquibase + JDBC rely on META-INF/services
}
