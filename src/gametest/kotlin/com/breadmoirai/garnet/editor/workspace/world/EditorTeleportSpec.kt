package com.breadmoirai.garnet.editor.workspace.world

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.writeStub
import com.breadmoirai.garnet.editor.explorer.data.EditorRoot
import com.breadmoirai.garnet.editor.explorer.data.EditorSession
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.core.async.onServer
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.math.abs

class EditorTeleportSpec : GarnetTestSpec({

    test("toFolder returns false for unknown subpath and does not change session") {
        withTempRoot("project-tp-unknown") { _ ->
            onServer {
                val player = makeMockServerPlayer(this)
                EditorSession.clear(player.uuid)
                val ok = EditorTeleport.toFolder(this, player, "does/not/exist")
                ok shouldBe false
                EditorSession.get(player.uuid).shouldBeNull()
            }
        }
    }

    test("toFolder teleports player to region and sets active subpath") {
        withTempRoot("project-tp-known") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                EditorSession.clear(player.uuid)
                EditorDimLifecycle.placeAll(this, EditorRoot(tmp))

                val region = EditorDimRegistry.of(this).regionOriginOf("set").shouldNotBeNull()
                val ok = EditorTeleport.toFolder(this, player, "set")
                ok shouldBe true

                (abs(player.x - (region.x + 0.5)) < 1e-6) shouldBe true
                (abs(player.z - (region.z + 0.5)) < 1e-6) shouldBe true
                player.y shouldBe (SharedSettings.projectGridYBase + 2).toDouble()
                EditorSession.get(player.uuid)?.activeSubpath shouldBe "set"

                EditorSession.clear(player.uuid)
                EditorWorld.clear(this)
            }
        }
    }
})
