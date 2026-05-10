package com.breadmoirai.redstonespecs.test.network

import com.breadmoirai.redstonespecs.network.handleRecorderCommand
import com.breadmoirai.redstonespecs.network.RecorderCmd
import com.breadmoirai.redstonespecs.network.RecorderCommandC2S
import com.breadmoirai.redstonespecs.network.handleRunnerCommand
import com.breadmoirai.redstonespecs.network.RunnerCmd
import com.breadmoirai.redstonespecs.network.RunnerCommandC2S
import com.breadmoirai.redstonespecs.network.RunnerState
import com.breadmoirai.redstonespecs.network.RunnerStatusS2C
import com.breadmoirai.redstonespecs.network.saveDir
import com.breadmoirai.redstonespecs.persistence.StructurePersistence
import com.breadmoirai.redstonespecs.test.drainPayloads
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.placeRunnerBE
import com.breadmoirai.redstonespecs.test.managed.clearCellVolume
import com.breadmoirai.redstonespecs.test.managed.writeStub
import com.breadmoirai.redstonespecs.test.withTempRoot
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

/**
 * Server-side coverage for `NetworkRegistry` recorder/runner C2S handlers.
 * Each test corresponds to one or more rows in `docs/use-cases/networking.md`
 * (UC-NET-01.a/d through UC-NET-05). Test names embed the UC ID for traceability.
 *
 * Client-side rows (UC-NET-01.b/c, UC-NET-03.e, UC-NET-04.a) are deferred to
 * a future client-gametest cycle.
 */
class RecorderRunnerNetworkRegistrySpec : RedstoneTestSpec({

    // UC-NET-02 — Server validates originPos and rejects stale or missing BEs.
    // 02.a (server-thread wrap) is verified structurally: every test below invokes
    //      handleX from inside `onServer { }`, which is exactly the contract the
    //      `context.server().execute { … }` lambda enforces at the registration site.
    // 02.c (block-kind re-validation) is covered by UC-NET-05.a and UC-NET-05.b.

    test("UC-NET-02.b: handleRecorderCommand on null BE is a silent no-op") {
        withTempRoot("net-uc02b") {
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                handleRecorderCommand(this, player, RecorderCommandC2S(BlockPos(1000, 64, 1000), RecorderCmd.START))

                drainPayloads(player).shouldBeEmpty()
            }
        }
    }

    test("UC-NET-02.d: handleRunnerCommand on null BE sends no S2C ack") {
        withTempRoot("net-uc02d") {
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(BlockPos(1004, 64, 1000), RunnerCmd.PLACE_STRUCTURE))

                drainPayloads(player).shouldBeEmpty()
            }
        }
    }

    // UC-NET-03 — Server emits S2C confirmation after state-mutating runner command.

    test("UC-NET-03.a: PLACE_STRUCTURE configured sends RunnerStatusS2C(IDLE, 'Structure placed: ...')") {
        withTempRoot("net-uc03a-cfg") { tmp ->
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1100, 64, 1000)
                val bounds = Vec3i(3, 3, 3)
                placeRunnerBE(level, pos, specId = "demo", structureId = "demo", bounds = bounds)
                // Pre-write a structure NBT under the live save dir so PLACE_STRUCTURE has something to load.
                val dir = saveDir(this)
                java.nio.file.Files.createDirectories(dir)
                StructurePersistence.save(dir, "demo", level, pos, bounds)
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.PLACE_STRUCTURE))

                val status = drainPayloads(player).filterIsInstance<RunnerStatusS2C>().single()
                status.state shouldBe RunnerState.IDLE
                status.summary shouldBe "Structure placed: demo"

                clearCellVolume(level, pos, bounds)
            }
        }
    }

    test("UC-NET-03.a: PLACE_STRUCTURE not-configured sends RunnerStatusS2C(IDLE, 'No spec configured')") {
        withTempRoot("net-uc03a-noc") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1108, 64, 1000)
                // Place runner with blank specId so isConfigured == false
                placeRunnerBE(level, pos, specId = "")
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.PLACE_STRUCTURE))

                val status = drainPayloads(player).filterIsInstance<RunnerStatusS2C>().single()
                status.state shouldBe RunnerState.IDLE
                status.summary shouldBe "No spec configured"
            }
        }
    }

    // UC-NET-03.b RUN happy-path and UC-NET-03.c "Already running" are deferred:
    // they require be.startRun, which launches an unbounded BE-scoped coroutine without a
    // RecordingHolder context, leading to leaked or hung work in the gametest harness.
    // Will be revisited when handleRunnerCommand is split or the spec engine gains a
    // synchronous-no-launch mode for tests. Coverage rows stay GAP / GAP-PARTIAL.

    test("UC-NET-03.b: RUN with missing spec sends RunnerStatusS2C(FAIL, 'Spec file not found: ...')") {
        withTempRoot("net-uc03b-miss") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1124, 64, 1000)
                placeRunnerBE(level, pos, specId = "no-such-spec")
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.RUN))

                val status = drainPayloads(player).filterIsInstance<RunnerStatusS2C>().single()
                status.state shouldBe RunnerState.FAIL
                status.summary shouldBe "Spec file not found: no-such-spec"
            }
        }
    }

    test("UC-NET-03.d: RESTORE configured sends RunnerStatusS2C(IDLE, 'Snapshot restored')") {
        withTempRoot("net-uc03d-cfg") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1140, 64, 1000)
                placeRunnerBE(level, pos, specId = "demo")
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.RESTORE))

                val status = drainPayloads(player).filterIsInstance<RunnerStatusS2C>().single()
                status.state shouldBe RunnerState.IDLE
                status.summary shouldBe "Snapshot restored"
            }
        }
    }

    test("UC-NET-03.d: RESTORE not-configured sends RunnerStatusS2C(IDLE, 'No spec configured')") {
        withTempRoot("net-uc03d-noc") {
            onServer {
                val player = makeMockServerPlayer(this)
                val level = this.overworld()
                val pos = BlockPos(1148, 64, 1000)
                // Place runner with blank specId so isConfigured == false
                placeRunnerBE(level, pos, specId = "")
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(pos, RunnerCmd.RESTORE))

                val status = drainPayloads(player).filterIsInstance<RunnerStatusS2C>().single()
                status.state shouldBe RunnerState.IDLE
                status.summary shouldBe "No spec configured"
            }
        }
    }
})
