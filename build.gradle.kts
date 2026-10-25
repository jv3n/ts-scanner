plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("com.diffplug.spotless") version "8.10.4"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    val ktor = "3.6.0"
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")

    implementation("io.github.cdimascio:dotenv-kotlin:6.5.1")

    // A tiny SLF4J backend for Ktor's logging, which avoids logback's logback.xml CVEs.
    implementation("org.slf4j:slf4j-simple:2.0.20")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

kotlin {
    jvmToolchain(25)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.5.0")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.5.0")
    }
}

application {
    mainClass.set("ts.scanner.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// Real 1-min bars into the test fixtures (#34) : `-Pcases=TICKER:YYYY-MM-DD,…`, Massive's key from
// `.env`, paced to the free plan's 5 requests a minute.
tasks.register<JavaExec>("fetchFixtures") {
    group = "verification"
    description = "Writes real 1-min bars into src/test/resources/bars/"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ts.scanner.fixtures.FetchFixturesKt")
    workingDir = projectDir
    args = (project.findProperty("cases") as String?)?.split(",").orEmpty()
}
