package com.breadmoirai.garnet.test

import androidx.compose.ui.input.key.Key
import com.breadmoirai.garnet.client.ui.compose.input.GlfwMods
import com.breadmoirai.garnet.client.ui.compose.input.glfwKeyToComposeKey
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.lwjgl.glfw.GLFW

class GlfwKeyMapSpec : ClientSpec({

    test("maps the navigation keys the tree needs") {
        glfwKeyToComposeKey(GLFW.GLFW_KEY_DOWN) shouldBe Key.DirectionDown
        glfwKeyToComposeKey(GLFW.GLFW_KEY_UP) shouldBe Key.DirectionUp
        glfwKeyToComposeKey(GLFW.GLFW_KEY_LEFT) shouldBe Key.DirectionLeft
        glfwKeyToComposeKey(GLFW.GLFW_KEY_RIGHT) shouldBe Key.DirectionRight
        glfwKeyToComposeKey(GLFW.GLFW_KEY_ENTER) shouldBe Key.Enter
    }

    test("maps the editing keys the text field needs") {
        glfwKeyToComposeKey(GLFW.GLFW_KEY_BACKSPACE) shouldBe Key.Backspace
        glfwKeyToComposeKey(GLFW.GLFW_KEY_DELETE) shouldBe Key.Delete
        glfwKeyToComposeKey(GLFW.GLFW_KEY_HOME) shouldBe Key.MoveHome
        glfwKeyToComposeKey(GLFW.GLFW_KEY_END) shouldBe Key.MoveEnd
        glfwKeyToComposeKey(GLFW.GLFW_KEY_A) shouldBe Key.A
    }

    test("returns null for an unmapped key rather than a wrong one") {
        glfwKeyToComposeKey(GLFW.GLFW_KEY_F24).shouldBeNull()
        glfwKeyToComposeKey(-1).shouldBeNull()
    }

    test("decodes GLFW modifier bits") {
        GlfwMods.shift(GLFW.GLFW_MOD_SHIFT).shouldBeTrue()
        GlfwMods.ctrl(GLFW.GLFW_MOD_CONTROL).shouldBeTrue()
        GlfwMods.alt(GLFW.GLFW_MOD_ALT).shouldBeTrue()
        GlfwMods.meta(GLFW.GLFW_MOD_SUPER).shouldBeTrue()
        GlfwMods.shift(GLFW.GLFW_MOD_CONTROL).shouldBeFalse()
        GlfwMods.ctrl(GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_CONTROL).shouldBeTrue()
    }
})
