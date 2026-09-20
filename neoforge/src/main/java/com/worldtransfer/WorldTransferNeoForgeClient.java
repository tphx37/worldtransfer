package com.worldtransfer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

import java.util.function.Consumer;
import java.util.function.Consumer;

@EventBusSubscriber(modid = WorldTransferConstants.MOD_ID, value = Dist.CLIENT)
public final class WorldTransferNeoForgeClient {
    private static IncomingTransfer offerScreenShownFor;

    static {
        TransferClientPlatform.install(new TransferClientPlatform() {
            @Override
            public void registerBlobHandler(Consumer<TransferNet.Blob> handler) {
            }

            @Override
            public void registerControlHandler(Consumer<TransferNet.Control> handler) {
            }

            @Override
            public void registerClientTick(Consumer<Minecraft> handler) {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> handler.accept(Minecraft.getInstance()));
            }

            @Override
            public void registerPauseMenu(Consumer<Screen> handler) {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (ScreenEvent.Init.Post event) -> {
                        currentInit = event;
                        try {
                            handler.accept(event.getScreen());
                        } finally {
                            currentInit = null;
                        }
                    });
            }

            @Override
            public void addPauseButton(Screen screen, Button button) {
                if (currentInit != null && currentInit.getScreen() == screen) {
                    currentInit.addListener(button);
                }
            }

            @Override
            public void send(TransferNet.Reply payload) {
                ClientPacketDistributor.sendToServer(payload);
            }

            @Override
            public void sendCommand(Minecraft client, String command) {
                if (client.player != null) {
                    client.player.connection.sendCommand(command);
                }
            }

            @Override
            public void openScreen(Minecraft client, Screen screen) {
                client.gui.setScreen(screen);
            }
        });
        WorldTransferClientGui.initialize();
    }

    private static ScreenEvent.Init.Post currentInit;

    private WorldTransferNeoForgeClient() {
    }

    @SubscribeEvent
    public static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(TransferNet.Blob.TYPE, (payload, context) -> IncomingTransfer.onBlob(payload));
        event.register(TransferNet.Control.TYPE, (payload, context) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && !payload.detail().isEmpty()) {
                client.player.sendSystemMessage(Component.literal(payload.detail()));
            }
        });
    }

}
