// Root of the Stele Gradle monorepo. Each layer is its own subproject so new
// ones (connectors, resolver, retriever, mcp-server, …) drop in as `include`s.
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

subprojects {
    group = "dev.stele"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}
