package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.managed.ManagedDimLifecycle
import com.breadmoirai.redstonespecs.managed.ManagedDimRegistry
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedSession
import com.breadmoirai.redstonespecs.managed.ManagedTeleport
import com.breadmoirai.redstonespecs.managed.ManagedWorld
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.withTempRoot
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.math.abs

class ManagedTeleportSpec : RedstoneTestSpec({

    test("toFolder returns false for unknown subpath and does not change session") {
        withTempRoot("managed-tp-unknown") { _ ->
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedSession.clear(player.uuid)
                val ok = ManagedTeleport.toFolder(this, player, "does/not/exist")
                ok shouldBe false
                ManagedSession.get(player.uuid).shouldBeNull()
            }
        }
    }

    test("toFolder teleports player to region and sets active subpath") {
        withTempRoot("managed-tp-known") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedSession.clear(player.uuid)
                ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))

                val region = ManagedDimRegistry.of(this).regionOriginOf("set").shouldNotBeNull()
                val ok = ManagedTeleport.toFolder(this, player, "set")
                ok shouldBe true

                (abs(player.x - (region.x + 0.5)) < 1e-6) shouldBe true
                (abs(player.z - (region.z + 0.5)) < 1e-6) shouldBe true
                player.y shouldBe (SharedSettings.managedGridYBase + 2).toDouble()
                ManagedSession.get(player.uuid)?.activeSubpath shouldBe "set"

                ManagedSession.clear(player.uuid)
                ManagedWorld.clear(this)
            }
        }
    }
})
