package com.breadmoirai.garnet.client.ui.compose

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Nothing in the client may reach for Jewel's `TextField` directly — `GarnetTextField` is the one
 * that works.
 *
 * A raw Jewel text field in the dock's scene renders with **no caret and no focused border**; the
 * wrapper exists solely because that fix is two-part wiring (the same interaction source handed to
 * both the widget and `focusInteractionBridge`) and therefore easy to omit. The failure mode is a
 * field that types perfectly and merely *looks* dead, which is exactly the kind of thing that
 * survives review, so this is pinned mechanically rather than left to memory.
 *
 * Source-scanning rather than compiling: a wrapper cannot make the wrapped API unreachable, so the
 * import is the only thing there is to check.
 */
class GarnetTextFieldUsageTest : StringSpec({

    val jewelTextFieldImport = "import org.jetbrains.jewel.ui.component.TextField"

    /** The repo root, found by walking up from the Gradle working directory (`versions/<mc>`). */
    fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "src/client/kotlin").isDirectory) dir = dir.parentFile
        return requireNotNull(dir) { "could not locate the repo root from ${System.getProperty("user.dir")}" }
    }

    val wrapper = "src/client/kotlin/com/breadmoirai/garnet/dock/compose/GarnetTextField.kt"

    "GarnetTextField is the only client file importing Jewel's TextField" {
        val root = repoRoot()
        val offenders = File(root, "src/client/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().lineSequence().any { line -> line.trim() == jewelTextFieldImport } }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .filterNot { it == wrapper }
            .toList()
        offenders.shouldBeEmpty()
    }

    // If the wrapper stops wrapping, the check above starts passing vacuously.
    "guard — the wrapper does wrap Jewel's TextField" {
        File(repoRoot(), wrapper).readText().contains(jewelTextFieldImport) shouldBe true
    }
})
