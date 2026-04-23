package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SpecMode
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpecPersistenceTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `save and load roundtrip`(@TempDir dir: Path) {
        val spec = RedstoneSpec.new("lever_lamp").copy(
            mode = SpecMode.TICK_AWARE,
            lifespan = 8,
            structure = "lever_lamp",
            bounds = BoundingBox(1, 0, 1, 5, 4, 5),
        )
        SpecPersistence.save(dir, spec)
        val loaded = SpecPersistence.load(dir, "lever_lamp")
        assertEquals(spec, loaded)
    }

    @Test
    fun `load returns null for unknown id`(@TempDir dir: Path) {
        assertNull(SpecPersistence.load(dir, "nonexistent"))
    }

    @Test
    fun `listIds returns saved ids`(@TempDir dir: Path) {
        SpecPersistence.save(dir, RedstoneSpec.new("alpha"))
        SpecPersistence.save(dir, RedstoneSpec.new("beta"))
        val ids = SpecPersistence.listIds(dir)
        assertTrue(ids.containsAll(listOf("alpha", "beta")))
    }
}
