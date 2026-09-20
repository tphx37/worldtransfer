package com.worldtransfer;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Loader boundary for server lifecycle, commands, and play networking. */
public interface TransferPlatform {
    void registerReplyHandler(ReplyHandler handler);

    void registerServerTick(Consumer<MinecraftServer> handler);

    void registerCommands(Consumer<com.mojang.brigadier.CommandDispatcher<CommandSourceStack>> handler);

    boolean canSend(ServerPlayer player);

    void send(ServerPlayer player, TransferNet.Blob payload);

    static TransferPlatform current() {
        return Holder.INSTANCE;
    }

    static void install(TransferPlatform platform) {
        Holder.INSTANCE = platform;
    }

    final class Holder {
        private static TransferPlatform INSTANCE = new TransferPlatform() {
            private UnsupportedOperationException unavailable() {
                return new UnsupportedOperationException("World Transfer platform bridge is not installed");
            }

            @Override public void registerReplyHandler(ReplyHandler handler) { throw unavailable(); }
            @Override public void registerServerTick(Consumer<MinecraftServer> handler) { throw unavailable(); }
            @Override public void registerCommands(Consumer<com.mojang.brigadier.CommandDispatcher<CommandSourceStack>> handler) { throw unavailable(); }
            @Override public boolean canSend(ServerPlayer player) { throw unavailable(); }
            @Override public void send(ServerPlayer player, TransferNet.Blob payload) { throw unavailable(); }
        };

        private Holder() {
        }
    }

    @FunctionalInterface
    interface ReplyHandler {
        void handle(MinecraftServer server, ServerPlayer player, TransferNet.Reply reply);
    }
}
