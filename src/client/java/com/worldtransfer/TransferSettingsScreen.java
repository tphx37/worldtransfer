package com.worldtransfer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TransferSettingsScreen extends Screen {
    private final Screen parent;
    private final TransferSettings settings;

    public TransferSettingsScreen(Screen parent, TransferSettings settings) {
        super(Component.literal("Transfer advanced settings"));
        this.parent = parent;
        this.settings = settings;
    }

    @Override
    protected void init() {
        int y = 40;
        addToggle("Player data: " + onOff(settings.includePlayerData), y,
            () -> settings.includePlayerData = !settings.includePlayerData);
        y += 24;
        addToggle("Advancements: " + onOff(settings.includeAdvancements), y,
            () -> settings.includeAdvancements = !settings.includeAdvancements);
        y += 24;
        addToggle("Entities: " + onOff(settings.includeEntities), y,
            () -> settings.includeEntities = !settings.includeEntities);
        y += 24;
        addToggle("World data: " + onOff(settings.includeWorldData), y,
            () -> settings.includeWorldData = !settings.includeWorldData);
        y += 32;

        addToggle("Carry over cheats: " + onOff(settings.carryCheats), y,
            () -> settings.carryCheats = !settings.carryCheats);
        y += 20;
        label(settings.carryCheats
            ? "Guests of the new host will be able to run commands"
            : "Safe: the copy is made with cheats off", y);
        y += 28;

        addRenderableWidget(Button.builder(
                Component.literal("Save ZIP to: " + (settings.saveToDesktop ? "Desktop" : "Minecraft folder")),
                button -> {
                    settings.saveToDesktop = !settings.saveToDesktop;
                    clearWidgets();
                    init();
                })
            .bounds(this.width / 2 - 125, y, 250, 20)
            .build());

        addRenderableWidget(Button.builder(Component.literal("Done"), button ->
                Minecraft.getInstance().setScreenAndShow(parent))
            .bounds(this.width / 2 - 100, this.height - 35, 200, 20)
            .build());
    }

    private void addToggle(String label, int y, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), button -> {
                action.run();
                clearWidgets();
                init();
            })
            .bounds(this.width / 2 - 125, y, 250, 20)
            .build());
    }

    private void label(String text, int y) {
        Button label = Button.builder(Component.literal(text), button -> { })
            .bounds(this.width / 2 - 145, y, 290, 20)
            .build();
        label.active = false;
        addRenderableWidget(label);
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }
}
