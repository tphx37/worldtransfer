package com.worldtransfer;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class WorldTransferFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricNetwork.register();
        TransferPlatform.install(new TransferPlatform() {
            @Override
            public void registerReplyHandler(ReplyHandler handler) {
                ServerPlayNetworking.registerGlobalReceiver(TransferNet.Reply.TYPE,
                    (payload, context) -> handler.handle(context.server(), context.player(), payload));
            }

            @Override
            public void registerServerTick(java.util.function.Consumer<net.minecraft.server.MinecraftServer> handler) {
                ServerTickEvents.END_SERVER_TICK.register(handler::accept);
            }

            @Override
            public void registerCommands(java.util.function.Consumer<com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>> handler) {
                CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> handler.accept(dispatcher));
            }

            @Override
            public boolean canSend(net.minecraft.server.level.ServerPlayer player) {
                return ServerPlayNetworking.canSend(player, TransferNet.Blob.TYPE);
            }

            @Override
            public void send(net.minecraft.server.level.ServerPlayer player, TransferNet.Blob payload) {
                ServerPlayNetworking.send(player, payload);
            }
        });
        WorldTransfer.initialize();
    }
}
