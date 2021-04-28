import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.30"
    application
}

val exposedVersion: String by project
val ktorVersion: String by project
group = "io.beatmaps"
version = "1.0-SNAPSHOT"

kotlin {
    sourceSets.all {
        languageSettings.useExperimentalAnnotation("kotlin.io.path.ExperimentalPathApi")
        languageSettings.useExperimentalAnnotation("io.ktor.locations.KtorExperimentalLocationsAPI")
        languageSettings.useExperimentalAnnotation("kotlinx.coroutines.flow.FlowPreview")
        languageSettings.useExperimentalAnnotation("kotlin.time.ExperimentalTime")
        languageSettings.useExperimentalAnnotation("io.ktor.util.KtorExperimentalAPI")
        languageSettings.useExperimentalAnnotation("kotlin.ExperimentalUnsignedTypes")
    }
}

dependencies {
    repositories {
        jcenter()
        mavenCentral()
        maven { url = uri("https://dl.bintray.com/kotlin/kotlin-js-wrappers") }
        maven { url = uri("https://dl.bintray.com/kotlin/kotlinx") }
        maven { url = uri("https://dl.bintray.com/kotlin/ktor") }
        maven { url = uri("https://jitpack.io") }
    }

    implementation("io.ktor:ktor-jackson:$ktorVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.6.1")

    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-html-builder:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:0.7.2")
    implementation("io.ktor:ktor-client-apache:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.2.3")

    // Database library
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")

    // Multimedia
    implementation("org.jaudiotagger:jaudiotagger:2.0.1")
    implementation("net.coobird:thumbnailator:0.4.13")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.6.1")
    implementation("org.sejda.imageio:webp-imageio:0.1.6")

    implementation("com.github.JUtupe:ktor-rabbitmq:0.2.0")
    implementation("com.rabbitmq:amqp-client:5.9.0")

    implementation("io.beatmaps:Common")
    implementation("io.beatmaps:CommonMP")
}

tasks.withType<KotlinCompile>() {
    dependsOn(gradle.includedBuild("Common").task(":build"))
    kotlinOptions.jvmTarget = "15"
}

application {
    mainClassName = "ServerKt"
}