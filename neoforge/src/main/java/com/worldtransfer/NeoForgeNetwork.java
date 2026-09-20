package com.worldtransfer;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.minecraft.server.level.ServerPlayer;

/** NeoForge registration for the shared transfer payload types. */
final class NeoForgeNetwork {
    static TransferPlatform.ReplyHandler replyHandler;
    private NeoForgeNetwork() {
    }

    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToClient(TransferNet.Blob.TYPE, TransferNet.Blob.CODEC);
        registrar.playToClient(TransferNet.Control.TYPE, TransferNet.Control.CODEC);
        registrar.playToServer(TransferNet.Reply.TYPE, TransferNet.Reply.CODEC,
            (payload, context) -> {
                if (replyHandler != null && context.player() instanceof ServerPlayer player) {
                    replyHandler.handle(player.level().getServer(), player, payload);
                }
            });
    }
}
