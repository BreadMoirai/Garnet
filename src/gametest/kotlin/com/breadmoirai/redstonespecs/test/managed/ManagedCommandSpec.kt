package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.managed.ManagedCommand
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedServerContext
import com.breadmoirai.redstonespecs.network.managed.ManagedTreeSnapshotS2C
import com.breadmoirai.redstonespecs.test.drainPayloads
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.withTempRoot
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import com.mojang.brigadier.CommandDispatcher
import io.kotest.matchers.shouldBe
import net.minecraft.commands.CommandSourceStack
import kotlin.io.path.createDirectories

class ManagedCommandSpec : RedstoneTestSpec({

    test("/redstonespecs managed without root configured sends an error message") {
        // Save and restore the global SharedSettings.managedRootPath since the gametest server is shared.
        val prior = SharedSettings.managedRootPath
        SharedSettings.managedRootPath = ""
        try {
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.clear(this)
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                ManagedCommand.register(dispatcher)

                val source = player.createCommandSourceStack()
                val rc = dispatcher.execute("redstonespecs managed", source)
                rc shouldBe 0
                // No tree snapshot should have been sent.
                val payloads = drainPayloads(player)
                payloads.filterIsInstance<ManagedTreeSnapshotS2C>().isEmpty() shouldBe true
            }
        } finally {
            SharedSettings.managedRootPath = prior
        }
    }

    test("/redstonespecs managed with context sends a ManagedTreeSnapshotS2C") {
        withTempRoot("managed-cmd-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                drainPayloads(player)
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                ManagedCommand.register(dispatcher)

                val source = player.createCommandSourceStack()
                val rc = dispatcher.execute("redstonespecs managed", source)
                (rc > 0) shouldBe true

                val snap = drainPayloads(player).filterIsInstance<ManagedTreeSnapshotS2C>().single()
                snap.leaves.map { it.subpath } shouldBe listOf("set")

                ManagedServerContext.clear(this)
            }
        }
    }
})
