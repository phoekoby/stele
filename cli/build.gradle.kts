plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
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
    implementation("com.charleskorn.kaml:kaml:0.61.0") // stele.yml config (YAML ↔ @Serializable)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
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
