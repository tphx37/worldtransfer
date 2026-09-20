package com.worldtransfer;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge bootstrap. Loader-specific registration stays here; transfer data and file algorithms
 * live in the common module, while the Fabric implementation remains isolated in the Fabric module.
 */
@Mod(WorldTransferNeoForge.MOD_ID)
public final class WorldTransferNeoForge {
    public static final String MOD_ID = WorldTransferConstants.MOD_ID;
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public WorldTransferNeoForge() {
        var modBus = ModLoadingContext.get().getActiveContainer().getEventBus();
        modBus.addListener(NeoForgeNetwork::registerPayloads);
        modBus.addListener(NeoForgeCapabilities::register);
        TransferPlatform.install(new TransferPlatform() {
            @Override
            public void registerReplyHandler(ReplyHandler handler) {
                NeoForgeNetwork.replyHandler = handler;
            }

            @Override
            public void registerServerTick(java.util.function.Consumer<net.minecraft.server.MinecraftServer> handler) {
                NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> handler.accept(event.getServer()));
            }

            @Override
            public void registerCommands(java.util.function.Consumer<com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>> handler) {
                NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> handler.accept(event.getDispatcher()));
            }

            @Override
            public boolean canSend(net.minecraft.server.level.ServerPlayer player) {
                return NetworkRegistry.hasChannel(player.connection, TransferNet.Blob.TYPE.id());
            }

            @Override
            public void send(net.minecraft.server.level.ServerPlayer player, TransferNet.Blob payload) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        });
        WorldTransfer.initialize();
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onServerStarting(ServerStartingEvent event) {
        LOGGER.debug("World Transfer NeoForge server bridge started");
    }

    private void onServerTick(ServerTickEvent.Post event) {
        // The NeoForge transfer service will consume this loader-neutral tick boundary.
    }
}
