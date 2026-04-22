package com.breadmoirai.redstonespecs.client.render

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.network.CycleSpecCaseC2SPayload
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.BlockHitResult

private val keyCycleForward = KeyMappingHelper.registerKeyMapping(
    KeyMapping("key.redstonespecs.cycle_forward", 93, KeyMapping.Category.MISC) // ] key
)
private val keyCycleBackward = KeyMappingHelper.registerKeyMapping(
    KeyMapping("key.redstonespecs.cycle_backward", 91, KeyMapping.Category.MISC) // [ key
)

fun registerHudOverlay() {
    HudElementRegistry.addLast(
        Identifier.fromNamespaceAndPath("redstonespecs", "spec_info"),
        HudElement { extractor, _ ->
            val mc = Minecraft.getInstance()
            if (mc.screen != null) return@HudElement
            val level = mc.level ?: return@HudElement
            val hitResult = mc.hitResult as? BlockHitResult ?: return@HudElement
            val hitPos = hitResult.blockPos

            val font = mc.font
            val y = extractor.guiHeight() / 2 + 20

            // Check if hit block belongs to a spec origin's bounds
            val ownerBe = SpecOriginBlockEntity.findFor(level, hitPos)
            if (ownerBe != null) {
                val spec = ownerBe.spec ?: return@HudElement
                val activeCase = spec.specCases.getOrNull(ownerBe.activeSpecCaseIndex)
                val relPos = hitPos.subtract(ownerBe.blockPos)
                val entry = activeCase?.entryAt(relPos)

                extractor.text(
                    font,
                    net.minecraft.network.chat.Component.literal(
                        "§6${spec.name} §7> §f${activeCase?.name ?: "?"} §7(${ownerBe.activeSpecCaseIndex + 1}/${spec.specCases.size})"
                    ),
                    2, y, 0xFFFFFF,
                )
                if (entry != null) {
                    extractor.text(
                        font,
                        net.minecraft.network.chat.Component.literal(
                            "  §7${entryTypeName(entry)} §f${entry.label.ifEmpty { relPos.toString() }}"
                        ),
                        2, y + 11, 0xFFFFFF,
                    )
                }
                extractor.text(
                    font,
                    net.minecraft.network.chat.Component.literal("§8[ ] cycle case"),
                    2, y + 22, 0xFFFFFF,
                )
                return@HudElement
            }

            // Check if looking directly at spec origin block
            val originBe = level.getBlockEntity(hitPos) as? SpecOriginBlockEntity
            if (originBe != null) {
                val spec = originBe.spec ?: return@HudElement
                extractor.text(
                    font,
                    net.minecraft.network.chat.Component.literal(
                        "§6${spec.name} §7(${originBe.activeSpecCaseIndex + 1}/${spec.specCases.size})"
                    ),
                    2, y, 0xFFFFFF,
                )
            }
        }
    )

    ClientTickEvents.END_CLIENT_TICK.register { mc ->
        if (mc.screen != null) return@register
        val level = mc.level ?: return@register
        val hitPos = (mc.hitResult as? BlockHitResult)?.blockPos ?: return@register

        val be = SpecOriginBlockEntity.findFor(level, hitPos)
            ?: (level.getBlockEntity(hitPos) as? SpecOriginBlockEntity)
            ?: return@register

        if (keyCycleForward.consumeClick()) {
            ClientPlayNetworking.send(CycleSpecCaseC2SPayload(be.blockPos, true))
        }
        if (keyCycleBackward.consumeClick()) {
            ClientPlayNetworking.send(CycleSpecCaseC2SPayload(be.blockPos, false))
        }
    }
}

private fun entryTypeName(entry: SpecEntry) = when (entry) {
    is InputSpec -> "Input"
    is OutputSpec -> "Output"
    is BreakpointSpec -> "Breakpoint"
    is AutoSpec -> "AutoSpec"
}
