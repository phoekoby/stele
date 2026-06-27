plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":core"))

    // java-tree-sitter (bonede): grammars ship prebuilt Win/Linux/macOS natives in-jar.
    implementation("io.github.bonede:tree-sitter:0.25.3")
    implementation("io.github.bonede:tree-sitter-kotlin:0.3.8.1")
    implementation("io.github.bonede:tree-sitter-java:0.23.5")
    implementation("io.github.bonede:tree-sitter-python:0.25.0")
    implementation("io.github.bonede:tree-sitter-go:0.25.0")
    implementation("io.github.bonede:tree-sitter-rust:0.24.0")
    implementation("io.github.bonede:tree-sitter-typescript:0.23.2")
    implementation("io.github.bonede:tree-sitter-javascript:0.25.0")
    implementation("io.github.bonede:tree-sitter-ruby:0.23.1")
    implementation("io.github.bonede:tree-sitter-c:0.24.1")
    implementation("io.github.bonede:tree-sitter-cpp:0.23.4")
    implementation("io.github.bonede:tree-sitter-c-sharp:0.23.1")
    implementation("io.github.bonede:tree-sitter-php:0.24.2")
    implementation("io.github.bonede:tree-sitter-swift:0.5.0")
    implementation("io.github.bonede:tree-sitter-scala:0.24.0")
    implementation("io.github.bonede:tree-sitter-lua:2.1.3a")

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
