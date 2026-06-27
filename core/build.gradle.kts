plugins {
    kotlin("jvm")
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Schema migrations. Liquibase has built-in SQLite support; Flyway 12 dropped
    // its plain SQLite module, so Liquibase is the clean SQLite fit.
    implementation("org.liquibase:liquibase-core:4.33.0")

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
