pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        maven("https://maven.terraformersmc.com/releases/")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9"
    kotlin("jvm") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.7" apply false
    id("co.uzzu.dotenv.gradle") version "4.0.0" apply false
}

stonecutter {
    create(rootProject) {
        versions("26.1")
        vcsVersion = "26.1"
    }
}

rootProject.name = "RedstoneSpecs"