package com.worldtransfer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Consumer;

/** Loader boundary for client payloads, ticks, and command dispatch. */
public interface TransferClientPlatform {
    void registerBlobHandler(Consumer<TransferNet.Blob> handler);

    void registerControlHandler(Consumer<TransferNet.Control> handler);

    void registerClientTick(Consumer<Minecraft> handler);

    void registerPauseMenu(Consumer<Screen> handler);

    void addPauseButton(Screen screen, Button button);

    void send(TransferNet.Reply payload);

    void sendCommand(Minecraft client, String command);

    void openScreen(Minecraft client, Screen screen);

    static TransferClientPlatform current() {
        return Holder.INSTANCE;
    }

    static void install(TransferClientPlatform platform) {
        Holder.INSTANCE = platform;
    }

    final class Holder {
        private static TransferClientPlatform INSTANCE = new TransferClientPlatform() {
            private UnsupportedOperationException unavailable() {
                return new UnsupportedOperationException("World Transfer client bridge is not installed");
            }

            @Override public void registerBlobHandler(Consumer<TransferNet.Blob> handler) { throw unavailable(); }
            @Override public void registerControlHandler(Consumer<TransferNet.Control> handler) { throw unavailable(); }
            @Override public void registerClientTick(Consumer<Minecraft> handler) { throw unavailable(); }
            @Override public void registerPauseMenu(Consumer<Screen> handler) { throw unavailable(); }
            @Override public void addPauseButton(Screen screen, Button button) { throw unavailable(); }
            @Override public void send(TransferNet.Reply payload) { throw unavailable(); }
            @Override public void sendCommand(Minecraft client, String command) { throw unavailable(); }
            @Override public void openScreen(Minecraft client, Screen screen) { throw unavailable(); }
        };

        private Holder() {
        }
    }
}
