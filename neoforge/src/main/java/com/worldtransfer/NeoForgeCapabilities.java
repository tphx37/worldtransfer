package com.worldtransfer;

import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** NeoForge capability registration boundary for future live transfer state. */
final class NeoForgeCapabilities {
    private NeoForgeCapabilities() {
    }

    static void register(RegisterCapabilitiesEvent event) {
        // Transfer state is persisted in the save; no capability is needed yet.
    }
}