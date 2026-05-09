package com.breadmoirai.redstonespecs.client.render

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.client.FaceHit
import com.breadmoirai.redstonespecs.client.HoveredFace
import com.breadmoirai.redstonespecs.client.currentHoveredFace
import com.breadmoirai.redstonespecs.client.findHoveredFace
import com.breadmoirai.redstonespecs.runner.EntryMarker
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.world.item.BlockItem
import net.minecraft.world.phys.BlockHitResult

private val keyCycleForward = KeyMappingHelper.registerKeyMapping(
    KeyMapping("key.redstonespecs.cycle_forward", 93, KeyMapping.Category.MISC) // ] key
)
private val keyCycleBackward = KeyMappingHelper.registerKeyMapping(
    KeyMapping("key.redstonespecs.cycle_backward", 91, KeyMapping.Category.MISC) // [ key
)

private val specBlocks = setOf(
    ModRegistries.REDSTONE_SPEC_RUNNER_BLOCK,
    ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK,
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
            val ownerBe = SpecBlockEntity.findFor(level, hitPos)
            if (ownerBe != null) {
                val relPos = hitPos.subtract(ownerBe.blockPos)
                val marker = ownerBe.specMarkers.firstOrNull { it.pos == relPos }

                extractor.text(
                    font,
                    net.minecraft.network.chat.Component.literal("§6${ownerBe.specId}"),
                    2, y, 0xFFFFFF,
                )
                if (marker != null) {
                    extractor.text(
                        font,
                        net.minecraft.network.chat.Component.literal(
                            "  §7${markerTypeName(marker)} §f${marker.label.ifEmpty { relPos.toString() }}"
                        ),
                        2, y + 11, 0xFFFFFF,
                    )
                }
                return@HudElement
            }

            // Check if looking directly at spec origin block
            val originBe = level.getBlockEntity(hitPos) as? SpecBlockEntity
            if (originBe != null) {
                extractor.text(
                    font,
                    net.minecraft.network.chat.Component.literal("§6${originBe.specId}"),
                    2, y, 0xFFFFFF,
                )
            }
        }
    )

    ClientTickEvents.END_CLIENT_TICK.register { mc ->
        if (mc.screen != null) {
            currentHoveredFace = null
            return@register
        }
        val level = mc.level ?: run { currentHoveredFace = null; return@register }

        // Face detection when holding SpecOrigin block item
        val player = mc.player
        val holdingRedstoneSpec = player != null && (
            (player.mainHandItem.item as? BlockItem)?.block in specBlocks ||
            (player.offhandItem.item as? BlockItem)?.block in specBlocks
        )

        if (holdingRedstoneSpec) {
            val eyePos = player.getEyePosition(1.0f)
            val lookVec = player.lookAngle
            val maxReach = 64.0

            var bestT = Double.MAX_VALUE
            var bestFace: HoveredFace? = null

            for (be in SpecBlockEntity.allFor(level)) {
                val b = be.specBounds
                val bpX = be.blockPos.x.toDouble()
                val bpY = be.blockPos.y.toDouble()
                val bpZ = be.blockPos.z.toDouble()
                val hit: FaceHit = findHoveredFace(
                    eyePos.x - bpX, eyePos.y - bpY, eyePos.z - bpZ,
                    lookVec.x, lookVec.y, lookVec.z,
                    0.0, 0.0, 0.0,
                    b.x.toDouble(), b.y.toDouble(), b.z.toDouble(),
                ) ?: continue
                if (hit.t < bestT && hit.t < maxReach) {
                    bestT = hit.t
                    bestFace = HoveredFace(be.blockPos, hit.axis, hit.isMax)
                }
            }
            currentHoveredFace = bestFace
        } else {
            currentHoveredFace = null
        }
    }
}

private fun markerTypeName(marker: EntryMarker) = when (marker.kind) {
    EntryMarker.Kind.INPUT -> "Input"
    EntryMarker.Kind.OUTPUT -> "Output"
}
