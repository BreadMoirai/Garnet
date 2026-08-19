package com.breadmoirai.garnet.camera

import com.breadmoirai.garnet.camera.network.CameraModeC2S
import com.breadmoirai.garnet.camera.network.CameraModeHandlers
import com.breadmoirai.garnet.core.async.onServer
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.test.makeMockServerPlayer
import io.kotest.matchers.shouldBe
import net.minecraft.world.level.GameType

/**
 * The gamemode half of camera mode. Needs a real `ServerPlayer` — `setGameMode` touches abilities,
 * the player list and the client sync packet — so it is a gametest rather than a unit test, per
 * `docs/gametest/unit-vs-gametest-split.md`.
 *
 * What is worth pinning here is *restore*: the handler deliberately keeps no previous-gamemode
 * state of its own and leans on the field vanilla already maintains. A future refactor that starts
 * storing one would pass a naive "enter then leave" check while silently breaking the case where
 * something else changed the player's gamemode in between.
 *
 * The other half worth pinning is *authority*: leave is gated on a grant the handler itself wrote
 * on a successful enter, so a spectator state the mod did not cause can never be undone through
 * `CameraModeC2S`. That gate is a security boundary, not a tidiness detail — see the escalation
 * case below.
 */
class CameraModeSpec : GarnetTestSpec({

    test("entering camera mode puts the player in spectator") {
        onServer {
            val player = makeMockServerPlayer(this)
            player.setGameMode(GameType.CREATIVE)

            CameraModeHandlers.handleCameraMode(this, player, CameraModeC2S(enter = true))

            player.gameMode() shouldBe GameType.SPECTATOR
        }
    }

    test("a player with no operator permission can still enter camera mode") {
        // Deliberately explicit: the handler elevates the command source, and a regression that
        // dropped the elevation would leave this feature working only for operators -- which is
        // exactly the kind of break that never shows up in a single-player smoke test.
        onServer {
            val player = makeMockServerPlayer(this)
            // makeMockServerPlayer registers through the normal player list, so this player is
            // not on the server's operator list -- no extra setup needed to make it non-op.

            CameraModeHandlers.handleCameraMode(this, player, CameraModeC2S(enter = true))

            player.gameMode() shouldBe GameType.SPECTATOR
        }
    }

    test("leaving cannot demote a player out of a spectator state camera mode never granted") {
        // The privilege-escalation case. An operator puts a player in spectator (moderation,
        // observing); enter is a server-side no-op because they are already spectating, so nothing
        // granted camera mode. A leave must then do nothing at all -- otherwise the handler
        // elevates itself to ALL_PERMISSIONS and hands an unprivileged player back whatever
        // previousGameModeForPlayer says, quite possibly CREATIVE, letting them walk straight out
        // of an operator-imposed spectator.
        onServer {
            val player = makeMockServerPlayer(this)
            player.setGameMode(GameType.CREATIVE)
            player.setGameMode(GameType.SPECTATOR) // as if an operator ran /gamemode spectator

            CameraModeHandlers.handleCameraMode(this, player, CameraModeC2S(enter = true))
            CameraModeHandlers.handleCameraMode(this, player, CameraModeC2S(enter = false))

            player.gameMode() shouldBe GameType.SPECTATOR
        }
    }

    test("leaving restores the gamemode the player had before, not a hardcoded default") {
        onServer {
            val player = makeMockServerPlayer(this)
            player.setGameMode(GameType.ADVENTURE) // deliberately NOT survival/creative

            CameraModeHandlers.handleCameraMode(this, player, CameraModeC2S(enter = true))
            CameraModeHandlers.handleCameraMode(this, player, CameraModeC2S(enter = false))

            player.gameMode() shouldBe GameType.ADVENTURE
        }
    }
})
