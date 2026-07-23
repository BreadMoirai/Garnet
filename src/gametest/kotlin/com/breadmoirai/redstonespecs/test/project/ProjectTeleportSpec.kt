package com.breadmoirai.redstonespecs.test.project

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.project.ProjectDimLifecycle
import com.breadmoirai.redstonespecs.project.ProjectDimRegistry
import com.breadmoirai.redstonespecs.project.ProjectRoot
import com.breadmoirai.redstonespecs.project.ProjectSession
import com.breadmoirai.redstonespecs.project.ProjectTeleport
import com.breadmoirai.redstonespecs.project.ProjectWorld
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.withTempRoot
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.createDirectories
import kotlin.math.abs

class ProjectTeleportSpec : RedstoneTestSpec({

    test("toFolder returns false for unknown subpath and does not change session") {
        withTempRoot("project-tp-unknown") { _ ->
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectSession.clear(player.uuid)
                val ok = ProjectTeleport.toFolder(this, player, "does/not/exist")
                ok shouldBe false
                ProjectSession.get(player.uuid).shouldBeNull()
            }
        }
    }

    test("toFolder teleports player to region and sets active subpath") {
        withTempRoot("project-tp-known") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectSession.clear(player.uuid)
                ProjectDimLifecycle.placeAll(this, ProjectRoot(tmp))

                val region = ProjectDimRegistry.of(this).regionOriginOf("set").shouldNotBeNull()
                val ok = ProjectTeleport.toFolder(this, player, "set")
                ok shouldBe true

                (abs(player.x - (region.x + 0.5)) < 1e-6) shouldBe true
                (abs(player.z - (region.z + 0.5)) < 1e-6) shouldBe true
                player.y shouldBe (SharedSettings.projectGridYBase + 2).toDouble()
                ProjectSession.get(player.uuid)?.activeSubpath shouldBe "set"

                ProjectSession.clear(player.uuid)
                ProjectWorld.clear(this)
            }
        }
    }
})
