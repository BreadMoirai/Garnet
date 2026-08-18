package com.breadmoirai.garnet.testing.data

import com.breadmoirai.garnet.core.spec.Phase
import com.breadmoirai.garnet.core.spec.SimTime
import com.breadmoirai.garnet.playback.data.BlockStateChange
import com.breadmoirai.garnet.playback.data.PropertyDiff
import com.breadmoirai.garnet.playback.data.RecordingSidecar
import com.breadmoirai.garnet.playback.data.StateRecording
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.LeverBlock
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

class SpecPersistenceTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    test("writeSpecKts then load round-trips a new-dsl spec") {
        val tmp = createTempDirectory("SpecPersistenceTest")
        // New-DSL path: write raw .spec.kts source, then load it back.
        val source = """
            import com.breadmoirai.garnet.core.spec.*
            import net.minecraft.core.Vec3i

            garnetSpec(id = "rt", bounds = Vec3i(3, 3, 3), lifespan = 10) {}
        """.trimIndent()
        SpecPersistence.writeSpecKts(tmp, "rt", source)
        tmp.resolve("rt.spec.kts").exists() shouldBe true

        val loaded = SpecPersistence.load(tmp, "rt")
        loaded shouldNotBe null
        loaded!!.id shouldBe "rt"
        loaded.bounds shouldBe Vec3i(3, 3, 3)
        loaded.lifespan shouldBe 10
    }

    test("writeSpecKts with sidecar recording roundtrips") {
        val tmp = createTempDirectory("SpecPersistenceTest-recording")
        val source = """
            import com.breadmoirai.garnet.core.spec.*
            import net.minecraft.core.Vec3i

            garnetSpec(id = "rec", bounds = Vec3i(3, 3, 3), lifespan = 5) {}
        """.trimIndent()
        SpecPersistence.writeSpecKts(tmp, "rec", source)

        val lever = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:lever"))
        val unpowered = lever.defaultBlockState().setValue(LeverBlock.POWERED, false)
        val pos = BlockPos(0, 0, 0)
        val recording = StateRecording(
            specId = UUID.randomUUID(),
            timestamp = 99999L,
            initialSnapshot = mapOf(pos to unpowered),
            changes = listOf(
                BlockStateChange(
                    pos = pos,
                    simTime = SimTime(1, Phase.SCHEDULED_TICKS, 0),
                    toBlock = null,
                    diffs = listOf(PropertyDiff("powered", "true")),
                )
            ),
        )

        RecordingSidecar.save(tmp, "rec", recording)
        tmp.resolve("rec.spec.kts").exists() shouldBe true
        tmp.resolve("rec.recording.nbt").exists() shouldBe true

        val loaded = SpecPersistence.loadRecording(tmp, "rec")
        loaded shouldNotBe null
        loaded!!.specId shouldBe recording.specId
        loaded.timestamp shouldBe recording.timestamp
        loaded.changes.size shouldBe recording.changes.size
    }

    test("no sidecar without explicit save") {
        val tmp = createTempDirectory("SpecPersistenceTest-no-recording")
        SpecPersistence.writeSpecKts(tmp, "norec", "import com.breadmoirai.garnet.core.spec.*\ngarnetSpec(id = \"norec\", lifespan = 5) {}")
        tmp.resolve("norec.recording.nbt").exists() shouldBe false
        SpecPersistence.loadRecording(tmp, "norec") shouldBe null
    }
})
