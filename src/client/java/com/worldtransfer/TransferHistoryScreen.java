package com.worldtransfer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Who has hosted this world, in order. Read straight from {@code world-transfer/history.json},
 * which travels with the save, so the chain survives every hand-over.
 */
public class TransferHistoryScreen extends Screen {
    private static final int MAX_SHOWN = 8;
    private final Screen parent;

    public TransferHistoryScreen(Screen parent) {
        super(Component.literal("Ownership history"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        List<TransferData.HistoryEntry> entries = readHistory();
        int y = 45;

        if (entries.isEmpty()) {
            label("This world has not been transferred yet.", y);
        } else {
            Collections.reverse(entries); // newest first
            int shown = Math.min(entries.size(), MAX_SHOWN);
            for (int i = 0; i < shown; i++) {
                TransferData.HistoryEntry entry = entries.get(i);
                label(entry.from() + "  ->  " + entry.to() + "   (" + shortDate(entry.at())
                    + ", MC " + entry.gameVersion() + ")", y);
                y += 24;
            }
            if (entries.size() > shown) {
                label("... and " + (entries.size() - shown) + " earlier transfers", y);
            }
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
            .bounds(this.width / 2 - 100, this.height - 35, 200, 20)
            .build());
    }

    private static List<TransferData.HistoryEntry> readHistory() {
        Minecraft client = Minecraft.getInstance();
        if (client.getSingleplayerServer() == null) {
            return List.of();
        }
        Path file = client.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
            .resolve(TransferData.HISTORY_FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return TransferData.parseHistory(Files.readString(file));
        } catch (Exception exception) {
            return List.of();
        }
    }

    /** Trims an ISO timestamp down to something that fits on a button. */
    private static String shortDate(String isoTimestamp) {
        int t = isoTimestamp.indexOf('T');
        if (t < 0) {
            return isoTimestamp;
        }
        String date = isoTimestamp.substring(0, t);
        String time = isoTimestamp.substring(t + 1);
        return date + " " + (time.length() >= 5 ? time.substring(0, 5) : time);
    }

    private void label(String text, int y) {
        Button label = Button.builder(Component.literal(text), button -> { })
            .bounds(this.width / 2 - 170, y, 340, 20)
            .build();
        label.active = false;
        addRenderableWidget(label);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }
}
