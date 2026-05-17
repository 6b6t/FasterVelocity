/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.fastervelocity;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * Maintains the set of commands and plugin channels that players are permitted to use.
 */
public final class CommandWhitelist {

  private static final Logger LOGGER = LogManager.getLogger(CommandWhitelist.class);

  private static final List<String> DEFAULT_PLUGIN_CHANNELS = List.of(
      "minecraft:brand",
      "minecraft:register",
      "minecraft:unregister"
  );
  private static final List<String> DEFAULT_COMMANDS = List.of(
      "server",
      "velocity:callback",
      "register",
      "reg",
      "unregister",
      "login",
      "l",
      "email",
      "changepassword",
      "confirmpassword",
      "totp",
      "captcha",
      "2fa",
      "verification",
      "help",
      "echochamber",
      "msg",
      "whisper",
      "w",
      "reply",
      "last",
      "kill",
      "suicide",
      "stats",
      "r",
      "ignore",
      "ignorehard",
      "ignorelist",
      "togglewhispering",
      "togglechat",
      "groupchat",
      "gc",
      "connectionmsgs",
      "deathmsgs",
      "sethome",
      "home",
      "homes",
      "homelist",
      "delhome",
      "tpa",
      "tpt",
      "tpn",
      "tpy",
      "tpyes",
      "tpno",
      "tps",
      "tptoggle",
      "hat",
      "skin",
      "hotspot",
      "buildermode",
      "particles",
      "nametag",
      "pvpmode",
      "togglespamchat",
      "freecam",
      "f",
      "vote",
      "discord",
      "website",
      "youtube",
      "twitter",
      "reddit",
      "instagram",
      "donate",
      "buy",
      "shop",
      "skins",
      "summon",
      "give",
      "chatcolor",
      "christmas",
      "balloons",
      "balloon",
      "sit",
      "link",
      "namecolor",
      "namecolors",
      "nc",
      "chatcolors",
      "cc",
      "invisframe",
      "thor",
      "playerstats"
  );

  private static volatile Set<String> commands = Collections.unmodifiableSet(
      new LinkedHashSet<>(DEFAULT_COMMANDS));
  private static volatile Set<String> pluginChannels = Collections.unmodifiableSet(
      new LinkedHashSet<>(DEFAULT_PLUGIN_CHANNELS));
  private static volatile boolean commandWhitelistEnabled = true;

  private static final String COMMAND_WHITELIST_ENABLED_KEY = "command-whitelist-enabled";
  private static final String COMMANDS_KEY = "commands";
  private static final String PLUGIN_CHANNELS_KEY = "plugin-channels";
  private static volatile Path whitelistPath;

  private CommandWhitelist() {
  }

  /**
   * Loads the command and plugin-channel whitelist from disk, creating a default file if needed.
   *
   * @param path the whitelist configuration path
   * @throws CommandWhitelistLoadException if the whitelist cannot be loaded
   */
  public static synchronized void initialize(Path path) throws CommandWhitelistLoadException {
    Objects.requireNonNull(path, "path");
    Path resolved = path.toAbsolutePath().normalize();
    ensureDefaultFile(resolved);
    whitelistPath = resolved;
    reloadInternal(resolved);
  }

  /**
   * Reloads the previously initialized whitelist configuration.
   *
   * @throws CommandWhitelistLoadException if the whitelist cannot be reloaded
   */
  public static synchronized void reload() throws CommandWhitelistLoadException {
    Path path = whitelistPath;
    if (path == null) {
      throw new CommandWhitelistLoadException("FasterVelocity configuration has not been initialized yet.");
    }
    reloadInternal(path);
  }

  /**
   * Checks whether a player command is allowed.
   *
   * @param input the command input, with or without a leading slash
   * @return whether the command is whitelisted
   */
  public static boolean isCommandWhitelisted(String input) {
    if (!commandWhitelistEnabled) {
      return true;
    }

    String commandWithoutSlash = input;
    if (input.startsWith("/")) {
      commandWithoutSlash = input.substring(1);
    }
    int spaceIndex = commandWithoutSlash.indexOf(' ');
    if (spaceIndex == -1) {
      return commands.contains(commandWithoutSlash);
    }
    String commandName = commandWithoutSlash.substring(0, spaceIndex);
    return commands.contains(commandName);
  }

  /**
   * Checks whether a plugin message channel is allowed.
   *
   * @param channel the plugin message channel
   * @return whether the channel is whitelisted
   */
  public static boolean isPluginChannelWhitelisted(String channel) {
    return pluginChannels.contains(channel);
  }

  private static void ensureDefaultFile(Path path) throws CommandWhitelistLoadException {
    if (Files.exists(path)) {
      if (!Files.isRegularFile(path)) {
        throw new CommandWhitelistLoadException(
            "FasterVelocity configuration path " + path + " exists but is not a file.");
      }
      return;
    }

    Path parent = path.getParent();
    try {
      if (parent != null && Files.notExists(parent)) {
        Files.createDirectories(parent);
      }
      try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        writer.write("# FasterVelocity configuration\n\n");
        writer.write("# Plugin message channels players are allowed to send\n");
        writer.write("plugin-channels:\n");
        for (String pluginChannel : DEFAULT_PLUGIN_CHANNELS) {
          writer.write("  - " + pluginChannel + "\n");
        }
        writer.write("\n");
        writer.write("# Whether to restrict player commands to the list below\n");
        writer.write("command-whitelist-enabled: true\n\n");
        writer.write("# Commands players are allowed to execute\n");
        writer.write("commands:\n");
        for (String command : DEFAULT_COMMANDS) {
          writer.write("  - " + command + "\n");
        }
      }
      LOGGER.info("Created default fastervelocity configuration at {}", path);
    } catch (IOException e) {
      throw new CommandWhitelistLoadException("Unable to create default fastervelocity configuration at "
          + path, e);
    }
  }

  private static void reloadInternal(Path path) throws CommandWhitelistLoadException {
    if (!Files.isRegularFile(path)) {
      throw new CommandWhitelistLoadException(
          "FasterVelocity configuration path " + path + " does not point to a file.");
    }

    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      Yaml yaml = new Yaml();
      Object data = yaml.load(reader);

      LinkedHashSet<String> parsedCommands;
      LinkedHashSet<String> parsedPluginChannels;
      boolean parsedCommandWhitelistEnabled;

      if (data == null) {
        parsedCommands = new LinkedHashSet<>(DEFAULT_COMMANDS);
        parsedPluginChannels = new LinkedHashSet<>(DEFAULT_PLUGIN_CHANNELS);
        parsedCommandWhitelistEnabled = true;
      } else if (data instanceof Iterable<?> iterable) {
        parsedCommands = parseCommands(iterable);
        parsedPluginChannels = new LinkedHashSet<>(DEFAULT_PLUGIN_CHANNELS);
        parsedCommandWhitelistEnabled = true;
        LOGGER.warn("FasterVelocity configuration at {} is using the deprecated list format.", path);
      } else if (data instanceof Map<?, ?> map) {
        parsedCommands = parseCommandsSection(map.get(COMMANDS_KEY));
        parsedPluginChannels = parsePluginChannelsSection(map.get(PLUGIN_CHANNELS_KEY));
        parsedCommandWhitelistEnabled =
            parseCommandWhitelistEnabled(map.get(COMMAND_WHITELIST_ENABLED_KEY));
      } else {
        throw new CommandWhitelistLoadException(
            "FasterVelocity configuration must be a YAML mapping or list.");
      }

      commands = Collections.unmodifiableSet(parsedCommands);
      pluginChannels = Collections.unmodifiableSet(parsedPluginChannels);
      commandWhitelistEnabled = parsedCommandWhitelistEnabled;
      LOGGER.info(
          "Loaded command whitelist (enabled: {}) with {} commands and {} whitelisted plugin "
              + "channels from {}.",
          commandWhitelistEnabled,
          commands.size(),
          pluginChannels.size(),
          path);
    } catch (IOException e) {
      throw new CommandWhitelistLoadException("Unable to read fastervelocity configuration at " + path, e);
    }
  }

  private static boolean parseCommandWhitelistEnabled(Object section)
      throws CommandWhitelistLoadException {
    if (section == null) {
      return true;
    }
    if (!(section instanceof Boolean enabled)) {
      throw new CommandWhitelistLoadException(
          "FasterVelocity configuration field 'command-whitelist-enabled' must be a boolean.");
    }
    return enabled;
  }

  private static LinkedHashSet<String> parseCommandsSection(Object commandsSection)
      throws CommandWhitelistLoadException {
    if (commandsSection == null) {
      return new LinkedHashSet<>(DEFAULT_COMMANDS);
    }
    if (!(commandsSection instanceof Iterable<?> iterable)) {
      throw new CommandWhitelistLoadException(
          "FasterVelocity configuration field 'commands' must be a YAML list.");
    }
    return parseCommands(iterable);
  }

  private static LinkedHashSet<String> parseCommands(Iterable<?> iterable)
      throws CommandWhitelistLoadException {
    LinkedHashSet<String> parsedCommands = new LinkedHashSet<>();
    for (Object element : iterable) {
      if (!(element instanceof String value)) {
        throw new CommandWhitelistLoadException(
            "Command whitelist entries must be strings: " + element);
      }
      String command = value.trim();
      if (command.isEmpty()) {
        throw new CommandWhitelistLoadException("Command whitelist contains an empty command.");
      }
      if (command.contains(" ")) {
        throw new CommandWhitelistLoadException(
            "Command whitelist entry contains spaces: '" + command + "'.");
      }
      parsedCommands.add(command);
    }
    return parsedCommands;
  }

  private static LinkedHashSet<String> parsePluginChannelsSection(Object section)
      throws CommandWhitelistLoadException {
    if (section == null) {
      return new LinkedHashSet<>(DEFAULT_PLUGIN_CHANNELS);
    }
    if (!(section instanceof Iterable<?> iterable)) {
      throw new CommandWhitelistLoadException(
          "FasterVelocity configuration field 'plugin-channels' must be a YAML list.");
    }
    LinkedHashSet<String> parsed = new LinkedHashSet<>();
    for (Object element : iterable) {
      if (!(element instanceof String value)) {
        throw new CommandWhitelistLoadException(
            "Plugin channel whitelist entries must be strings: " + element);
      }
      String channel = value.trim();
      if (channel.isEmpty()) {
        throw new CommandWhitelistLoadException("Plugin channel whitelist contains an empty entry.");
      }
      parsed.add(channel);
    }
    return parsed;
  }

  /**
   * Raised when the FasterVelocity whitelist configuration cannot be loaded.
   */
  public static class CommandWhitelistLoadException extends Exception {
    CommandWhitelistLoadException(String message) {
      super(message);
    }

    CommandWhitelistLoadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
