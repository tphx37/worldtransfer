package com.worldtransfer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;

public class SkinFace extends AbstractWidget {
    private final PlayerSkin skin;

    public SkinFace(int x, int y, PlayerSkin skin) {
        super(x, y, 48, 48, Component.empty());
        this.skin = skin;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(
            graphics, skin, getX(), getY(), 48);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, Component.empty());
    }
}