import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
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

sourceSets {
    create("testBridge") {
        compileClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].compileClasspath
        runtimeClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].runtimeClasspath
    }
    create("clientTest") {
        compileClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].compileClasspath +
            sourceSets["testBridge"].output
        runtimeClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].runtimeClasspath +
            sourceSets["testBridge"].output
    }
}

loom {
    mods.register("redstonespecs-clienttest") {
        sourceSet("clientTest")
    }
}

configurations {
    named("testBridgeImplementation") {
        extendsFrom(configurations["clientImplementation"])
    }
    named("testBridgeCompileOnly") {
        extendsFrom(configurations["clientCompileOnly"])
    }
    named("testBridgeRuntimeOnly") {
        extendsFrom(configurations["clientRuntimeOnly"])
    }
    named("clientTestImplementation") {
        extendsFrom(configurations["clientImplementation"])
        extendsFrom(configurations["testBridgeImplementation"])
    }
    named("clientTestCompileOnly") {
        extendsFrom(configurations["clientCompileOnly"])
        extendsFrom(configurations["testBridgeCompileOnly"])
    }
    named("clientTestRuntimeOnly") {
        extendsFrom(configurations["clientRuntimeOnly"])
        extendsFrom(configurations["testBridgeRuntimeOnly"])
    }
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "redstonespecs-gametest"
        enableGameTests = true
        enableClientGameTests = false
        eula = true
    }
}

// Loom creates the `gametest` source set during project evaluation, so it must be configured in `afterEvaluate`.
afterEvaluate {
    sourceSets.findByName("gametest")?.let { gt ->
        gt.compileClasspath += sourceSets["testBridge"].output
        gt.runtimeClasspath += sourceSets["testBridge"].output
    }
    configurations.findByName("gametestImplementation")?.extendsFrom(configurations["testBridgeImplementation"])
    configurations.findByName("gametestCompileOnly")?.extendsFrom(configurations["testBridgeCompileOnly"])
    configurations.findByName("gametestRuntimeOnly")?.extendsFrom(configurations["testBridgeRuntimeOnly"])
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
    maven {
        url = uri("https://maven.pkg.github.com/livefront/auto-emit")
        credentials {
            username = env.GPR_USER.value
            password = env.GPR_KEY.value
        }
    }
}



dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    implementation("com.livefront.autoemit:annotation:0.1.0")
    ksp("com.livefront.autoemit:generate:0.1.0")

    implementation("com.terraformersmc:modmenu:${property("modmenu_version")}")
    implementation("dev.isxander:yet-another-config-lib:${property("yacl_version")}")

    // Kotlin scripting host for .spec.kts authoring (data/serial/KtsSpecLoader.kt)
    implementation(kotlin("scripting-common"))
    implementation(kotlin("scripting-jvm"))
    implementation(kotlin("scripting-jvm-host"))

    // KotlinPoet for emitting .spec.kts source from RedstoneSpec (data/serial/KtsSpecEmitter.kt)
    implementation("com.squareup:kotlinpoet:1.18.1")

    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")
    "clientTestImplementation"(fabricApi.module("fabric-client-gametest-api-v1", project.property("fabric_version") as String))
    "testBridgeImplementation"(fabricApi.module("fabric-client-gametest-api-v1", project.property("fabric_version") as String))

    testImplementation("net.fabricmc:fabric-loader-junit:${project.property("loader_version")}")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation(sourceSets["testBridge"].output)

    // Kotlin coroutines (also pulled transitively via fabric-language-kotlin, but declare explicitly)
    "testBridgeImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Kotest engine + assertions (used by testBridge, gametest, clientTest, and test source sets)
    "testBridgeImplementation"("io.kotest:kotest-runner-junit5:5.9.1")
    "testBridgeImplementation"("io.kotest:kotest-assertions-core:5.9.1")

    // Kensa — modular artifacts; Kotest integration lives in kensa-assertions-kotest
    "testBridgeImplementation"("dev.kensa:kensa-framework-junit:0.5.10")
    "testBridgeImplementation"("dev.kensa:kensa-assertions-kotest:0.5.10")
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
        jvmArgs(
            "-Dlog4j2.logger.redstonespecs.name=Redstone Specs",
            "-Dlog4j2.logger.redstonespecs.level=DEBUG",
            // Mockito 5.x bundles Byte Buddy which only officially supports up to Java 24.
            // Java 25 is used here; this flag lets Byte Buddy instrument Java 25 classes experimentally.
            "-Dnet.bytebuddy.experimental=true",
            // Mockito self-attaches a Java agent at runtime; suppress the JDK dynamic-agent warning.
            "-XX:+EnableDynamicAgentLoading",
        )
    }

    named<JavaExec>("runGameTest") {
        jvmArgs(
            "-Dlog4j2.logger.redstonespecs.name=Redstone Specs",
            "-Dlog4j2.logger.redstonespecs.level=DEBUG",
        )
    }

    register<JavaExec>("runClientTest") {
        group = "fabric"
        description = "Runs FabricClientGameTest flows from the clientTest sourceset."

        val clientTestSrc = sourceSets["clientTest"]
        classpath = clientTestSrc.runtimeClasspath
        mainClass.set("net.fabricmc.devlaunchinjector.Main")
        // loom sets workingDir to the version subproject dir; projectDirectory IS versions/26.1 for :26.1:
        workingDir = project.layout.projectDirectory.asFile

        val launchCfg = project.layout.projectDirectory
            .dir(".gradle/loom-cache")
            .file("launch.cfg")
            .asFile
        val testResources = clientTestSrc.resources.srcDirs.first()

        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "-Dfabric.dli.config=${launchCfg.absolutePath}",
                "-Dfabric.dli.env=client",
                "-Dfabric.client.gametest",
                "-Dfabric.client.gametest.testModResourcesPath=${testResources.absolutePath}",
                "-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient",
                "--sun-misc-unsafe-memory-access=allow",
                "--enable-native-access=ALL-UNNAMED",
            )
        })

        jvmArgs(
            "-Dlog4j2.logger.redstonespecs.name=Redstone Specs",
            "-Dlog4j2.logger.redstonespecs.level=DEBUG",
        )

        dependsOn("clientTestClasses", "generateDLIConfig")
    }

}
