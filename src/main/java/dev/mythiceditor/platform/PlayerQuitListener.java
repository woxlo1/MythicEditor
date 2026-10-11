package dev.mythiceditor.platform;

import java.util.function.Supplier;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import dev.mythiceditor.core.service.EditorCore;

/** Ends a player's web sessions when they leave the server, if enabled in config.yml. */
public final class PlayerQuitListener implements Listener {
    // The EditorCore instance is replaced when /mythiceditor reload restarts it, so never hold it directly.
    private final Supplier<EditorCore> core;

    public PlayerQuitListener(Supplier<EditorCore> core) {
        this.core = core;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        EditorCore c = core.get();
        if (c != null && c.settings.invalidateOnQuit) {
            c.sessions.invalidateForPlayer(event.getPlayer().getUniqueId());
        }
    }
}
