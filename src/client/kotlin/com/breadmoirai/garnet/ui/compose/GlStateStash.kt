package com.breadmoirai.garnet.ui.compose

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30

/**
 * Pure OpenGL bookkeeping used by [ComposeSurface] to hand the shared GL context back to Blaze3D in
 * exactly the state it was in before Skia touched it. See [ComposeSurface]'s "GL-state coexistence"
 * doc for why this exists; the functions here are the mechanics that doc describes.
 */
internal object GlStateStash {

    // --- GL-state snapshot/restore ---------------------------------------------------------------
    // Slots: 0 program, 1 VAO, 2 active-texture, 3 tex-binding-2D(unit0), 4 array-buffer,
    //        5 draw-fbo, 6 read-fbo, 7 blend, 8 depth-test, 9 scissor, 10 cull.
    const val SAVE_SLOTS = 11

    fun saveGlState(o: IntArray) {
        o[0] = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        o[1] = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        o[2] = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        o[3] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        o[4] = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
        o[5] = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        o[6] = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        o[7] = if (GL11.glIsEnabled(GL11.GL_BLEND)) 1 else 0
        o[8] = if (GL11.glIsEnabled(GL11.GL_DEPTH_TEST)) 1 else 0
        o[9] = if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) 1 else 0
        o[10] = if (GL11.glIsEnabled(GL11.GL_CULL_FACE)) 1 else 0
    }

    fun restoreGlState(o: IntArray) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, o[5])
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, o[6])
        GL30.glBindVertexArray(o[1])
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, o[4])
        GL13.glActiveTexture(o[2])
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, o[3])
        GL20.glUseProgram(o[0])
        setEnabled(GL11.GL_BLEND, o[7])
        setEnabled(GL11.GL_DEPTH_TEST, o[8])
        setEnabled(GL11.GL_SCISSOR_TEST, o[9])
        setEnabled(GL11.GL_CULL_FACE, o[10])
    }

    private fun setEnabled(cap: Int, on: Int) {
        if (on == 1) GL11.glEnable(cap) else GL11.glDisable(cap)
    }

    // --- GL pixel-store (unpack) snapshot/reset --------------------------------------------------
    // Skia's drawImage uploads a CPU raster via glTexSubImage2D, which reads the pixel buffer using
    // the current GL_UNPACK_* state. Blaze3D leaves GL_UNPACK_ROW_LENGTH / SKIP_PIXELS set from its
    // own texture writes; inherited, they roll/shear Skia's upload (the horizontal wraparound the
    // asymmetric Compose panel exposed — the old symmetric plain-Skia panel didn't upload anything,
    // so never hit it). We reset these to their GL defaults around the draw and restore MC's values
    // after, keeping GlStateManager's belief intact. Slots: 0 align,1 row_len,2 skip_px,3 skip_rows.

    fun saveAndResetUnpack(): IntArray {
        val o = IntArray(4)
        o[0] = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)
        o[1] = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH)
        o[2] = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS)
        o[3] = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0)
        return o
    }

    fun restoreUnpack(o: IntArray) {
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, o[0])
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, o[1])
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, o[2])
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, o[3])
    }
}
