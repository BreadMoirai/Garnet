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

// Create clientTest sourceset early so Loom's runs DSL can reference it via source().
// Classpath wiring against main/client is deferred to afterEvaluate (those sourcesets
// are created by loom.splitEnvironmentSourceSets() and fabricApi.configureTests()).
val clientTestSourceSet = sourceSets.create("clientTest")

loom {
    splitEnvironmentSourceSets()

    mods {
        register("redstonespecs") {
            sourceSet("main")
            sourceSet("client")
        }
        register("redstonespecs-clienttest") {
            sourceSet(clientTestSourceSet)
        }
    }

    runs {
        register("clientTest") {
            client()
            source(clientTestSourceSet)
            property("fabric.client.gametest", "true")
            vmArg("-Dlog4j2.logger.redstonespecs.name=Redstone Specs")
            vmArg("-Dlog4j2.logger.redstonespecs.level=DEBUG")
            vmArg("--sun-misc-unsafe-memory-access=allow")
            vmArg("--enable-native-access=ALL-UNNAMED")
        }
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

// Loom creates the `gametest` source set during project evaluation.
// Wire client-only deps so `gametest` can compile against client APIs.
configurations {
    named("gametestImplementation") {
        extendsFrom(configurations["clientImplementation"])
    }
    named("gametestCompileOnly") {
        extendsFrom(configurations["clientCompileOnly"])
    }
    named("gametestRuntimeOnly") {
        extendsFrom(configurations["clientRuntimeOnly"])
    }
    named("clientTestImplementation") {
        extendsFrom(configurations["clientImplementation"])
    }
    named("clientTestCompileOnly") {
        extendsFrom(configurations["clientCompileOnly"])
    }
    named("clientTestRuntimeOnly") {
        extendsFrom(configurations["clientRuntimeOnly"])
    }
}

afterEvaluate {
    sourceSets.named("gametest") {
        compileClasspath += sourceSets["client"].output
        runtimeClasspath += sourceSets["client"].output
    }
    clientTestSourceSet.apply {
        compileClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].compileClasspath
        runtimeClasspath += sourceSets["main"].output +
            sourceSets["client"].output +
            sourceSets["client"].runtimeClasspath
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
    // Kotest engine + assertions ship in main: used by .spec.kts at runtime AND by all dev test source sets.
    implementation("io.kotest:kotest-runner-junit5:5.9.1")
    implementation("io.kotest:kotest-assertions-core:5.9.1")

    // kotlinx-coroutines-core (also pulled by fabric-language-kotlin transitively, declared explicitly).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.mockito:mockito-core:5.14.2")

    "clientTestImplementation"(fabricApi.module("fabric-client-gametest-api-v1", project.property("fabric_version") as String))

    // MixinExtras (@WrapOperation etc.) is bundled inside fabric-loader at runtime, so it is only
    // needed on the compile/annotation-processor classpath — hence compileOnly, not implementation,
    // to avoid double-bundling. Version tracks the copy fabric-loader ships. Used by the client
    // source set's viewport-composite present mixin.
    "clientCompileOnly"("io.github.llamalad7:mixinextras-fabric:0.5.3")
    "clientAnnotationProcessor"("io.github.llamalad7:mixinextras-fabric:0.5.3")
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
            "--sun-misc-unsafe-memory-access=allow",
            "--enable-native-access=ALL-UNNAMED",
        )
    }

}
