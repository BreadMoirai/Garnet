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
    // Compose compiler plugin — versioned in lockstep with Kotlin (2.3.20). Enables @Composable
    // compilation for the Compose-in-MC spike (docs/ui/compose-in-mc-feasibility.md). We take only
    // the Kotlin compiler plugin + direct runtime deps, NOT the org.jetbrains.compose Gradle plugin,
    // to avoid fighting Loom/Stonecutter's source-set + run wiring.
    kotlin("plugin.compose") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.7" apply false
    id("co.uzzu.dotenv.gradle") version "4.0.0" apply false
}

stonecutter {
    create(rootProject) {
        versions("26.2")
        vcsVersion = "26.2"
    }
}

rootProject.name = "RedstoneSpecs"