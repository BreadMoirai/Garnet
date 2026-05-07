package com.breadmoirai.redstonespecs.test

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import org.slf4j.LoggerFactory

/**
 * Client-side gametest harness for the recorder → editor → runner flow.
 *
 * The previous suite ran the flow once per [SpecMode] value with mode-aware
 * scenario assertions and direct InputSpec/OutputSpec construction. With the
 * data-layer redesign (single SpecEntry, no SpecMode), the suite needs to be
 * re-authored against the flat model. Tracked as a follow-up.
 */
@Suppress("UnstableApiUsage")
class RedstonespecsClientTests : FabricClientGameTest {
    private val logger = LoggerFactory.getLogger("Redstone Specs")

    override fun runTest(context: ClientGameTestContext) {
        logger.info("[RedstonespecsClientTests] placeholder run — re-author against flat SpecEntry model")
    }
}
