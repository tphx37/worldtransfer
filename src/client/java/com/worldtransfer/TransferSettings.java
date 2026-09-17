package com.worldtransfer;

public final class TransferSettings {
    public boolean includePlayerData = true;
    public boolean includeAdvancements = true;
    public boolean includeEntities = true;
    public boolean includeWorldData = true;
    public boolean saveToDesktop = true;
    /**
     * Whether the transferred copy keeps the world's "cheats" flag. Off by default: with
     * {@code allowCommands} set, everyone who joins the new host's LAN game gets command access.
     */
    public boolean carryCheats = false;

    /** Encodes the settings as the options token the /worldtransfer command parses. */
    public String asCommandArguments() {
        return (includePlayerData ? "playerdata " : "noplayerdata ")
            + (includeAdvancements ? "advancements " : "noadvancements ")
            + (includeEntities ? "entities " : "noentities ")
            + (includeWorldData ? "worlddata " : "noworlddata ")
            + (carryCheats ? "cheats " : "nocheats ")
            + (saveToDesktop ? "desktop" : "minecraft");
    }
}
