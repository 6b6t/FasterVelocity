package com.velocitypowered.proxy.command.builtin;

import abomination.CommandWhitelist;
import abomination.CommandWhitelist.CommandWhitelistLoadException;
import com.mojang.brigadier.Command;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.proxy.plugin.virtual.VelocityVirtualPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reloads the Abomination configuration from disk. Only the console can execute this command.
 */
public final class ReloadAbominationCommand {

  private static final Logger LOGGER = LogManager.getLogger(ReloadAbominationCommand.class);
  private final ProxyServer server;

  public ReloadAbominationCommand(ProxyServer server) {
    this.server = server;
  }

  public void register() {
    BrigadierCommand command = new BrigadierCommand(
        BrigadierCommand.literalArgumentBuilder("reload")
            .requires(source -> source == server.getConsoleCommandSource())
            .executes(context -> execute(context.getSource()))
            .build());

    server.getCommandManager().register(
        server.getCommandManager().metaBuilder("abomination:reload")
            .aliases("reload")
            .plugin(VelocityVirtualPlugin.INSTANCE)
            .build(),
        command
    );
  }

  private int execute(CommandSource source) {
    try {
      CommandWhitelist.reload();
      source.sendMessage(Component.text("Abomination configuration reloaded.", NamedTextColor.GREEN));
      return Command.SINGLE_SUCCESS;
    } catch (CommandWhitelistLoadException e) {
      LOGGER.error("Unable to reload abomination configuration", e);
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      source.sendMessage(Component.text(
          "Failed to reload abomination configuration: " + message, NamedTextColor.RED));
      return 0;
    }
  }
}
