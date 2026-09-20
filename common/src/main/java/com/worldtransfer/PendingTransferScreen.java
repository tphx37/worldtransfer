package com.worldtransfer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PendingTransferScreen extends Screen {
    private static final Pattern TARGET_NAME = Pattern.compile("\\\"targetPlayer\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SOURCE_NAME = Pattern.compile("\\\"sourcePlayer\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private final Screen parent;
    private final Path manifest;

    public PendingTransferScreen(Screen parent, Path manifest) {
        super(Component.literal("Pending host transfer"));
        this.parent = parent;
        this.manifest = manifest;
    }

    @Override
    protected void init() {
        String text = "Waiting for handoff";
        String target = "Unknown player";
        String source = "Unknown host";
        boolean targetPlayer = false;
        try {
            String json = Files.readString(manifest);
            target = readValue(json, TARGET_NAME, target);
            source = readValue(json, SOURCE_NAME, source);
            text = "Restore " + target + " using saved data from " + source;
            if (Minecraft.getInstance().player != null) {
                targetPlayer = Minecraft.getInstance().player.getName().getString().equalsIgnoreCase(target);
            }
        } catch (Exception ignored) {
        }

        Button infoButton = Button.builder(Component.literal(text), button -> {})
            .bounds(this.width / 2 - 150, this.height / 2 - 60, 300, 20)
            .build();
        infoButton.active = false;
        addRenderableWidget(infoButton);

        Button waitButton = Button.builder(Component.literal("Waiting in background..."), button -> { })
            .bounds(this.width / 2 - 150, this.height / 2 - 10, 300, 20)
            .build();
        waitButton.active = false;
        addRenderableWidget(waitButton);

        Button skipButton = Button.builder(Component.literal("Skip waiting and continue"), button -> {
                TransferClientPlatform.current().sendCommand(Minecraft.getInstance(), "worldtransfer continue");
                    TransferClientPlatform.current().openScreen(Minecraft.getInstance(), parent);
            })
            .bounds(this.width / 2 - 150, this.height / 2 + 40, 300, 20)
            .build();
        skipButton.active = Minecraft.getInstance().player != null && targetPlayer;
        addRenderableWidget(skipButton);

        if (!targetPlayer) {
            Button targetOnlyHint = Button.builder(Component.literal("Only the target player can continue this transfer"), button -> {})
                .bounds(this.width / 2 - 150, this.height / 2 + 80, 300, 20)
                .build();
            targetOnlyHint.active = false;
            addRenderableWidget(targetOnlyHint);
        }
    }

    private static String readValue(String json, Pattern pattern, String fallback) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    @Override
    public void onClose() {
        TransferClientPlatform.current().openScreen(Minecraft.getInstance(), parent);
    }
}
