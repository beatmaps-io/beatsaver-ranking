import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.30-RC"
    id("org.jlleitschuh.gradle.ktlint") version "10.1.0"
    application
}

val exposedVersion: String by project
val ktorVersion: String by project
group = "io.beatmaps"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain {
        (this as JavaToolchainSpec).languageVersion.set(JavaLanguageVersion.of(15))
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

    implementation("io.ktor:ktor-jackson:$ktorVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.6.1")

    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-html-builder:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.7.3")
    implementation("io.ktor:ktor-client-apache:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.2.3")

    // Database library
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.litote.kmongo:kmongo-coroutine:4.2.8")

    implementation("io.beatmaps:BeatMaps-Common:+")
    implementation("io.beatmaps:BeatMaps-CommonMP:+")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "15"
}

application {
    mainClass.set("io.beatmaps.scoresaber.ServerKt")
}
