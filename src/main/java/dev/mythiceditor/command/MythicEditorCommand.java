package dev.mythiceditor.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import dev.mythiceditor.MythicEditorPlugin;
import dev.mythiceditor.core.service.EditorCore;
import dev.mythiceditor.core.web.SessionManager;

/** /mythiceditor [open|reload|sessions|revoke <token>] */
public final class MythicEditorCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("open", "reload", "sessions", "revoke");

    // The EditorCore is replaced when "reload" restarts the editor, so always go through the plugin.
    private final MythicEditorPlugin plugin;

    public MythicEditorCommand(MythicEditorPlugin plugin) {
        this.plugin = plugin;
    }

    /** The running editor, or null (after telling the sender) if it is not running. */
    private EditorCore running(CommandSender sender) {
        EditorCore core = plugin.core();
        if (core == null) {
            sender.sendMessage(prefixed(Component.text("MythicEditor is not running. Check the console, fix config.yml and run /mythiceditor reload.", NamedTextColor.RED)));
        }
        return core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "open" -> open(sender);
            case "reload" -> reload(sender);
            case "sessions" -> sessions(sender);
            case "revoke" -> revoke(sender, args);
            default -> sender.sendMessage(help());
        }
        return true;
    }

    private void open(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(prefixed(Component.text("This command must be run in-game so the editor can be linked to your account.", NamedTextColor.RED)));
            return;
        }
        if (!player.hasPermission("mythiceditor.use")) {
            sender.sendMessage(prefixed(Component.text("You do not have permission to use MythicEditor.", NamedTextColor.RED)));
            return;
        }
        EditorCore core = running(sender);
        if (core == null) return;
        if (core.settings.relayEnabled && !core.relayConnected()) {
            sender.sendMessage(prefixed(Component.text(
                "The relay link to Cloudflare is not connected yet, so this link may not open. Check relay.url / relay.secret in config.yml and the console.", NamedTextColor.YELLOW)));
        }
        String url = core.issueEditorUrl(player.getUniqueId(), player.getName());
        Component link = Component.text(url, NamedTextColor.AQUA)
            .decorate(TextDecoration.UNDERLINED)
            .clickEvent(ClickEvent.openUrl(url))
            .hoverEvent(HoverEvent.showText(Component.text("Click to open the MythicEditor web IDE")));
        player.sendMessage(prefixed(Component.text("Web Editorを開く", NamedTextColor.GRAY)));
        player.sendMessage(link);
        player.sendMessage(prefixed(Component.text("This link expires in " + core.settings.ticketTtlSeconds + " seconds and can be used once.", NamedTextColor.DARK_GRAY)));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("mythiceditor.admin")) {
            sender.sendMessage(prefixed(Component.text("You do not have permission to reload MythicEditor.", NamedTextColor.RED)));
            return;
        }
        MythicEditorPlugin.ReloadResult result = plugin.reloadEditor();
        switch (result.outcome()) {
            case REINDEXED -> sender.sendMessage(prefixed(Component.text(
                "config.yml is unchanged. Reindexed MythicMobs content (" + result.changedFiles() + " file(s) changed).", NamedTextColor.GREEN)));
            case RESTARTED -> {
                EditorCore core = plugin.core();
                sender.sendMessage(prefixed(Component.text(
                    "Reloaded config.yml and restarted the editor" + (core != null ? " (" + core.publicUrl() + ")" : "")
                        + ". Active web sessions were ended - run /mythiceditor again for a new link.", NamedTextColor.GREEN)));
            }
            case INVALID_CONFIG -> sender.sendMessage(prefixed(Component.text(
                "config.yml could not be read; keeping the current settings. See the console.", NamedTextColor.RED)));
            case ROLLED_BACK -> sender.sendMessage(prefixed(Component.text(
                "The new config.yml failed to start; went back to the previous settings. See the console.", NamedTextColor.YELLOW)));
            case FAILED -> sender.sendMessage(prefixed(Component.text(
                "MythicEditor failed to restart. See the console, fix config.yml and run /mythiceditor reload again.", NamedTextColor.RED)));
        }
    }

    private void sessions(CommandSender sender) {
        if (!sender.hasPermission("mythiceditor.admin")) {
            sender.sendMessage(prefixed(Component.text("You do not have permission to view MythicEditor sessions.", NamedTextColor.RED)));
            return;
        }
        EditorCore core = running(sender);
        if (core == null) return;
        List<SessionManager.Session> all = core.sessions.allSessions();
        if (all.isEmpty()) {
            sender.sendMessage(prefixed(Component.text("No active web sessions.", NamedTextColor.GRAY)));
            return;
        }
        sender.sendMessage(prefixed(Component.text(all.size() + " active session(s):", NamedTextColor.GRAY)));
        for (SessionManager.Session s : all) {
            long idleSec = (System.currentTimeMillis() - s.lastSeen().get()) / 1000;
            sender.sendMessage(Component.text("  " + s.playerName() + "  id=" + SessionManager.publicId(s.token()) + "  idle=" + idleSec + "s", NamedTextColor.DARK_GRAY));
        }
    }

    private void revoke(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mythiceditor.admin")) {
            sender.sendMessage(prefixed(Component.text("You do not have permission to revoke MythicEditor sessions.", NamedTextColor.RED)));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(prefixed(Component.text("Usage: /mythiceditor revoke <player>", NamedTextColor.RED)));
            return;
        }
        EditorCore core = running(sender);
        if (core == null) return;
        String targetName = args[1];
        int revoked = 0;
        for (SessionManager.Session s : core.sessions.allSessions()) {
            if (s.playerName().equalsIgnoreCase(targetName)) {
                core.sessions.invalidate(s.token());
                revoked++;
            }
        }
        sender.sendMessage(prefixed(Component.text("Revoked " + revoked + " session(s) for " + targetName + ".", NamedTextColor.GREEN)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return SUBS.stream().filter(s -> s.startsWith(p)).collect(Collectors.toList());
        }
        EditorCore core = plugin.core();
        if (core != null && args.length == 2 && args[0].equalsIgnoreCase("revoke")) {
            List<String> names = new ArrayList<>();
            for (SessionManager.Session s : core.sessions.allSessions()) names.add(s.playerName());
            return names;
        }
        return List.of();
    }

    /** Called from PlayerQuitListener to end a player's web sessions when they leave, if configured. */
    public void onPlayerQuit(UUID playerId) {
        EditorCore core = plugin.core();
        if (core != null && core.settings.invalidateOnQuit) core.sessions.invalidateForPlayer(playerId);
    }

    private static Component prefixed(Component c) {
        return Component.text("[MythicEditor] ", NamedTextColor.GOLD).append(c);
    }

    private static Component help() {
        return prefixed(Component.text("Usage: /mythiceditor [open|reload|sessions|revoke <player>]", NamedTextColor.YELLOW));
    }
}
