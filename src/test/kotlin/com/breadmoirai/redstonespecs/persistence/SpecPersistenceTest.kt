package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists

class SpecPersistenceTest {
    @Test
    fun `save then load round-trips a spec via spec_kts`(@TempDir tmp: Path) {
        val spec = redstoneSpec("rt") {
            bounds(3, 3, 3)
            lifespan = 10
            input(1, 0, 1, label = "in") { atStart { powered() } }
            output(2, 0, 2, label = "out") { at(tick = 5) { lit() } }
        }
        SpecPersistence.save(tmp, spec)
        assertTrue(tmp.resolve("rt.spec.kts").exists())

        val loaded = SpecPersistence.load(tmp, "rt")
        assertEquals(spec, loaded)
    }
}
