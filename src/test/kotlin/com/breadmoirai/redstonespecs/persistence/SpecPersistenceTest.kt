package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.dsl.SimTime
import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import com.breadmoirai.redstonespecs.runner.BlockStateChange
import com.breadmoirai.redstonespecs.runner.PropertyDiff
import com.breadmoirai.redstonespecs.runner.StateRecording
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
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

    test("save then load round-trips a spec via spec_kts") {
        val tmp = createTempDirectory("SpecPersistenceTest")
        val spec = redstoneSpec("rt") {
            bounds(3, 3, 3)
            lifespan = 10
            input(1, 0, 1, label = "in") { atStart { powered() } }
            output(2, 0, 2, label = "out") { at(tick = 5) { lit() } }
        }
        SpecPersistence.save(tmp, spec)
        tmp.resolve("rt.spec.kts").exists() shouldBe true

        val loaded = SpecPersistence.load(tmp, "rt")
        loaded shouldBe spec
    }

    test("save with recording writes sidecar and loadRecording returns it") {
        val tmp = createTempDirectory("SpecPersistenceTest-recording")
        val spec = redstoneSpec("rec") {
            bounds(3, 3, 3)
            lifespan = 5
        }
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

        SpecPersistence.save(tmp, spec, recording)
        tmp.resolve("rec.spec.kts").exists() shouldBe true
        tmp.resolve("rec.recording.nbt").exists() shouldBe true

        val loaded = SpecPersistence.loadRecording(tmp, "rec")
        loaded shouldNotBe null
        loaded!!.specId shouldBe recording.specId
        loaded.timestamp shouldBe recording.timestamp
        loaded.changes.size shouldBe recording.changes.size
    }

    test("save without recording leaves no sidecar") {
        val tmp = createTempDirectory("SpecPersistenceTest-no-recording")
        val spec = redstoneSpec("norec") {
            bounds(3, 3, 3)
            lifespan = 5
        }
        SpecPersistence.save(tmp, spec)
        tmp.resolve("norec.recording.nbt").exists() shouldBe false
        SpecPersistence.loadRecording(tmp, "norec") shouldBe null
    }
})
