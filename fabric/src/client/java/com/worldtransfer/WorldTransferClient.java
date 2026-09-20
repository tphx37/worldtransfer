package com.worldtransfer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class WorldTransferClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TransferClientPlatform.install(new TransferClientPlatform() {
            @Override
            public void registerBlobHandler(java.util.function.Consumer<TransferNet.Blob> handler) {
                ClientPlayNetworking.registerGlobalReceiver(TransferNet.Blob.TYPE,
                    (payload, context) -> handler.accept(payload));
            }

            @Override
            public void registerControlHandler(java.util.function.Consumer<TransferNet.Control> handler) {
                ClientPlayNetworking.registerGlobalReceiver(TransferNet.Control.TYPE,
                    (payload, context) -> handler.accept(payload));
            }

            @Override
            public void registerClientTick(java.util.function.Consumer<Minecraft> handler) {
                ClientTickEvents.END_CLIENT_TICK.register(handler::accept);
            }

            @Override
            public void registerPauseMenu(java.util.function.Consumer<Screen> handler) {
                ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> handler.accept(screen));
            }

            @Override
            public void addPauseButton(Screen screen, Button button) {
                ((com.worldtransfer.mixin.ScreenAccessor) screen).worldtransfer$addRenderableWidget(button);
            }

            @Override
            public void send(TransferNet.Reply payload) {
                ClientPlayNetworking.send(payload);
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
        TransferClientPlatform.current().registerBlobHandler(IncomingTransfer::onBlob);
        TransferClientPlatform.current().registerControlHandler(payload -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && !payload.detail().isEmpty()) {
                client.player.sendSystemMessage(Component.literal(payload.detail()));
            }
        });
        WorldTransferClientGui.initialize();
    }
}
