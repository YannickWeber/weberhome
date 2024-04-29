plugins {
    kotlin("jvm") version "1.9.20"
    application
}

group = "de.weberhome"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.influxdb:influxdb-client-java:6.9.0")
    implementation("org.tinylog:tinylog:1.3.6")
    implementation("com.ghgande:j2mod:3.1.1")
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(21)
}