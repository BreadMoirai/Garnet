import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.20"
    id("net.fabricmc.fabric-loom")
    id("maven-publish")
}

version = "${project.property("mod_version")}+${sc.current.version}"
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val requiredJava = when {
    sc.current.parsed >= "26" -> 25
    sc.current.parsed >= "1.17" -> 21
    else -> 8
}
java.toolchain.languageVersion.set(JavaLanguageVersion.of(requiredJava))

loom {
    splitEnvironmentSourceSets()

    mods {
        register("redstonespecs") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}


repositories {
    maven("https://maven.gegy.dev") {
        name = "Gegy"
    }
    maven("https://maven.terraformersmc.com/releases/") {
        name = "TerraformersMC"
    }
    maven("https://maven.isxander.dev/releases") {
        name = "Xander Maven"
    }
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    implementation("com.terraformersmc:modmenu:${property("modmenu_version")}")
    implementation("dev.isxander:yet-another-config-lib:${property("yacl_version")}")

    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    testImplementation("net.fabricmc:fabric-loader-junit:${project.property("loader_version")}")
}

tasks {
    processResources {
        notCompatibleWithConfigurationCache("I don't know why...")
        inputs.property("version", project.version)
        inputs.property("minecraft_version", project.property("minecraft_version"))
        inputs.property("loader_version", project.property("loader_version"))

        filesMatching("fabric.mod.json") {
            expand(
                "version" to project.version,
                "minecraft_version" to project.property("minecraft_version") as String,
                "loader_version" to project.property("loader_version") as String,
                "kotlin_loader_version" to project.property("kotlin_loader_version") as String,
            )
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }

    withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(requiredJava.toString()))
    }

    // Builds the version into a shared folder in `build/libs/${mod version}/`
    register<Copy>("buildAndCollect") {
        group = "build"
        from(jar.map { it.archiveFile }, jar.map { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod_version")}"))
        dependsOn("jar")
    }

    jar {
        from("LICENSE") {
            rename { "${it}_${project.base.archivesName.get()}" }
        }
    }

    test {
        useJUnitPlatform()
    }
}
