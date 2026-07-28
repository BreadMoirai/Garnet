import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
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
    // Widens the package-private com.mojang.blaze3d.opengl GL-backend classes the Compose-in-MC spike
    // needs to fetch a raw GL framebuffer id (see garnet.accesswidener + ComposeSurface.kt).
    // rootProject.file: Stonecutter's per-version subproject shares the root `src/`, so resolve the
    // widener against the repo root, not versions/<v>/.
    accessWidenerPath.set(rootProject.file("src/main/resources/garnet.classtweaker"))

    splitEnvironmentSourceSets()

    mods {
        register("garnet") {
            sourceSet("main")
            sourceSet("client")
        }
        register("garnet-clienttest") {
            sourceSet(clientTestSourceSet)
        }
    }

    runs {
        register("clientTest") {
            client()
            source(clientTestSourceSet)
            property("fabric.client.gametest", "true")
            vmArg("-Dlog4j2.logger.garnet.name=Garnet")
            vmArg("-Dlog4j2.logger.garnet.level=DEBUG")
            vmArg("--sun-misc-unsafe-memory-access=allow")
            vmArg("--enable-native-access=ALL-UNNAMED")
        }
    }
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "garnet-gametest"
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
    // Compose Multiplatform transitive deps: androidx.* KMP artifacts live on Google's Maven, and
    // pre-release Compose builds live in the JetBrains compose-dev space repo (the 1.12.0-beta line
    // used here is on Central, but the dev repo is a safe fallback for its transitive graph).
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") {
        name = "Compose Dev"
    }
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

    // KotlinPoet for emitting .spec.kts source from GarnetSpec (data/serial/KtsSpecEmitter.kt)
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

    // === Compose-in-MC feasibility spike (docs/ui/compose-in-mc-feasibility.md) ==================
    // Skiko is JetBrains' Skia binding; the `skiko-awt-runtime-<platform>` artifact bundles the
    // desktop-GL Skia native for that platform. This project's dev/runtime host is Windows-x64
    // (runClient(Test) launches via cmd.exe on Windows), MC 26.2 ships LWJGL 3.4.1 + JDK 25, and
    // Skiko 0.150.1 is the desktop-GL build Compose Multiplatform 1.12.x targets. We take Skiko
    // directly (not the Compose Gradle plugin) so the Skia-over-Blaze3D-GL coexistence — the actual
    // spike risk — is proven without dragging in the @Composable compiler. If this platform detail
    // ever needs to be cross-platform, switch to `org.jetbrains.skiko:skiko-awt` + per-OS runtime.
    "clientImplementation"("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.150.1")

    // Compose Multiplatform runtime (Step 3): a REAL ComposeScene renders to a Skia canvas, replacing
    // the plain-Skia proof panel. We pin 1.12.0-beta02 because its transitive skiko-awt is 0.150.1 —
    // an EXACT match for the desktop-GL natives above (a mismatch risks a skiko version-guard failure
    // or native ABI break). Coordinates use the explicit `-desktop` variant: without the Compose Gradle
    // plugin there are no KMP target attributes to resolve the aggregator coords to the desktop artifact.
    // material3 is deliberately omitted (its version diverged from the Compose BOM — only 1.12.0-alpha03
    // exists, which would drag ui/foundation to alpha03 and a different skiko); the Button is built from
    // foundation's clickable + hoverable + InteractionSource, which is pure Compose interaction plumbing.
    //
    // `runtime` is `clientImplementation`-scoped, same as `ui`/`foundation`, keeping Compose out of
    // the server jar entirely (see docs/build/compose-runtime-scoping.md). This only works because
    // the compiler-plugin-classpath strip below removes the Compose compiler subplugin from the
    // non-client `KotlinCompile` tasks; without that strip, the project-wide Compose compiler plugin's
    // VersionChecker fails `main`/`test`/`gametest` compilation for lacking the runtime on their classpath.
    "clientImplementation"("org.jetbrains.compose.runtime:runtime-desktop:1.12.0-beta02")
    "clientImplementation"("org.jetbrains.compose.ui:ui-desktop:1.12.0-beta02")
    "clientImplementation"("org.jetbrains.compose.foundation:foundation-desktop:1.12.0-beta02")
}

// Compose compiler plugin is applied project-wide (plugins {}); it only needs to run on the
// compilations that contain @Composable code (client, clientTest). Strip it from the others
// (main, test, gametest) by filtering the Compose subplugin out of their KotlinCompile
// pluginClasspath — this is what lets `runtime-desktop` above stay client-scoped instead of
// sitting on the base `implementation` for every source set. See
// docs/build/compose-runtime-scoping.md for what was tried and why this approach was chosen.
listOf("compileKotlin", "compileTestKotlin", "compileGametestKotlin").forEach { name ->
    tasks.findByName(name)?.let { t ->
        (t as org.jetbrains.kotlin.gradle.tasks.KotlinCompile).pluginClasspath.setFrom(
            t.pluginClasspath.filter { !it.name.contains("compose") }
        )
    }
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
            "-Dlog4j2.logger.garnet.name=Garnet",
            "-Dlog4j2.logger.garnet.level=DEBUG",
            // Mockito 5.x bundles Byte Buddy which only officially supports up to Java 24.
            // Java 25 is used here; this flag lets Byte Buddy instrument Java 25 classes experimentally.
            "-Dnet.bytebuddy.experimental=true",
            // Mockito self-attaches a Java agent at runtime; suppress the JDK dynamic-agent warning.
            "-XX:+EnableDynamicAgentLoading",
        )
    }

    named<JavaExec>("runGameTest") {
        jvmArgs(
            "-Dlog4j2.logger.garnet.name=Garnet",
            "-Dlog4j2.logger.garnet.level=DEBUG",
            "--sun-misc-unsafe-memory-access=allow",
            "--enable-native-access=ALL-UNNAMED",
        )
    }

}
