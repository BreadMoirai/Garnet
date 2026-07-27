package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.ide.ProjectTreeState
import com.breadmoirai.redstonespecs.network.project.StructureResultS2C
import com.breadmoirai.redstonespecs.testing.ClientSpec
import io.kotest.matchers.shouldBe

class StructureExplorerSpec : ClientSpec({
    test("onStructureResult surfaces the message as Explorer status") {
        runOnClient {
            ProjectTreeState.reset()
            ProjectTreeState.onStructureResult(StructureResultS2C("a/box.nbt", 2, 1, 3, "placed a/box.nbt"))
        }
        ProjectTreeState.status shouldBe "placed a/box.nbt"
    }
})
