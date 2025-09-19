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
 * Reloads the command whitelist from disk. Only the console can execute this command.
 */
public final class ReloadWhitelistCommand {

  private static final Logger LOGGER = LogManager.getLogger(ReloadWhitelistCommand.class);
  private final ProxyServer server;

  public ReloadWhitelistCommand(ProxyServer server) {
    this.server = server;
  }

  public void register() {
    BrigadierCommand command = new BrigadierCommand(
        BrigadierCommand.literalArgumentBuilder("reloadwhitelist")
            .requires(source -> source == server.getConsoleCommandSource())
            .executes(context -> execute(context.getSource()))
            .build());

    server.getCommandManager().register(
        server.getCommandManager().metaBuilder(command)
            .plugin(VelocityVirtualPlugin.INSTANCE)
            .build(),
        command
    );
  }

  private int execute(CommandSource source) {
    try {
      CommandWhitelist.reload();
      source.sendMessage(Component.text("Command whitelist reloaded.", NamedTextColor.GREEN));
      return Command.SINGLE_SUCCESS;
    } catch (CommandWhitelistLoadException e) {
      LOGGER.error("Unable to reload command whitelist", e);
      String message = e.getMessage() == null ? e.toString() : e.getMessage();
      source.sendMessage(Component.text(
          "Failed to reload command whitelist: " + message, NamedTextColor.RED));
      return 0;
    }
  }
}

