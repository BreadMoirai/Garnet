package com.breadmoirai.garnet.editor.network

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

object EditorNetworkRegistry {

    fun register() {
        // Payload-type registrations
        PayloadTypeRegistry.serverboundPlay().register(ListEditorTreeC2S.TYPE, ListEditorTreeC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(LoadEditorFolderC2S.TYPE, LoadEditorFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(UnloadEditorFolderC2S.TYPE, UnloadEditorFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SaveNowC2S.TYPE, SaveNowC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(NewEditorSpecC2S.TYPE, NewEditorSpecC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SetEditorRootC2S.TYPE, SetEditorRootC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(PlaceStructureC2S.TYPE, PlaceStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SaveStructureC2S.TYPE, SaveStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(NewStructureC2S.TYPE, NewStructureC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(CreateFolderC2S.TYPE, CreateFolderC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RenamePathC2S.TYPE, RenamePathC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(DuplicatePathC2S.TYPE, DuplicatePathC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(DeletePathC2S.TYPE, DeletePathC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(MovePathC2S.TYPE, MovePathC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(UndoC2S.TYPE, UndoC2S.STREAM_CODEC)
        PayloadTypeRegistry.serverboundPlay().register(RedoC2S.TYPE, RedoC2S.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(UndoStateS2C.TYPE, UndoStateS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(EditorTreeSnapshotS2C.TYPE, EditorTreeSnapshotS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(EditorFolderLoadedS2C.TYPE, EditorFolderLoadedS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(EditorSaveReportS2C.TYPE, EditorSaveReportS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(EditorErrorS2C.TYPE, EditorErrorS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(StructureResultS2C.TYPE, StructureResultS2C.STREAM_CODEC)
        PayloadTypeRegistry.clientboundPlay().register(StructureAutoSavedS2C.TYPE, StructureAutoSavedS2C.STREAM_CODEC)

        ServerPlayNetworking.registerGlobalReceiver(ListEditorTreeC2S.TYPE) { _, ctx ->
            ctx.server().execute { EditorTreeHandlers.handleListTree(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(LoadEditorFolderC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorTreeHandlers.handleLoadFolder(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(UnloadEditorFolderC2S.TYPE) { _, ctx ->
            ctx.server().execute { EditorTreeHandlers.handleUnload(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(SaveNowC2S.TYPE) { _, ctx ->
            ctx.server().execute { EditorTreeHandlers.handleSaveNow(ctx.server(), ctx.player()) }
        }
        ServerPlayNetworking.registerGlobalReceiver(NewEditorSpecC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorTreeHandlers.handleNewSpec(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(SetEditorRootC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorTreeHandlers.handleSetRoot(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(PlaceStructureC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorStructureHandlers.handlePlaceStructure(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(SaveStructureC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorStructureHandlers.handleSaveStructure(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(NewStructureC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorStructureHandlers.handleNewStructure(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(CreateFolderC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorFileOpsHandlers.handleCreateFolder(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(RenamePathC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorFileOpsHandlers.handleRename(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(DuplicatePathC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorFileOpsHandlers.handleDuplicate(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(DeletePathC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorFileOpsHandlers.handleDelete(ctx.server(), ctx.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(MovePathC2S.TYPE) { payload, ctx ->
            ctx.server().execute { EditorFileOpsHandlers.handleMove(ctx.server(), ctx.player(), payload) }
        }
    }
}
