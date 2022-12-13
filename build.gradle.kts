import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    kotlin("jvm") version "1.7.10"
    id("org.jlleitschuh.gradle.ktlint") version "11.0.0"
    application
}

val exposedVersion: String by project
val ktorVersion: String by project
group = "io.beatmaps"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(16))
    }
    sourceSets.all {
        languageSettings.optIn("kotlin.io.path.ExperimentalPathApi")
        languageSettings.optIn("io.ktor.locations.KtorExperimentalLocationsAPI")
        languageSettings.optIn("kotlinx.coroutines.flow.FlowPreview")
        languageSettings.optIn("kotlin.time.ExperimentalTime")
        languageSettings.optIn("io.ktor.util.KtorExperimentalAPI")
        languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
    }
}

dependencies {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://artifactory.kirkstall.top-cat.me") }
    }

    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-jackson:$ktorVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.12.5")

    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.7.3")
    implementation("io.ktor:ktor-client-apache:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.2.5")

    // Database library
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    implementation("io.beatmaps:BeatMaps-CommonMP:+")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "16"
}

application {
    mainClass.set("io.beatmaps.scoresaber.ServerKt")
}

ktlint {
    reporters {
        reporter(ReporterType.CHECKSTYLE)
    }
}
