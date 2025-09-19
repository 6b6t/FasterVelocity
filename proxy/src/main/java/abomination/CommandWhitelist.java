package abomination;

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
 * Maintains the set of commands that players are permitted to run.
 */
public final class CommandWhitelist {

  private static final Logger LOGGER = LogManager.getLogger(CommandWhitelist.class);

  private static final List<String> DEFAULT_COMMANDS = List.of(
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
  private static volatile boolean packetCapturesEnabled;

  private static final String COMMANDS_KEY = "commands";
  private static final String PACKET_CAPTURES_KEY = "packet-captures";
  private static final String PACKET_CAPTURES_ENABLED_KEY = "enabled";
  private static volatile Path whitelistPath;

  private CommandWhitelist() {
  }

  public static synchronized void initialize(Path path) throws CommandWhitelistLoadException {
    Objects.requireNonNull(path, "path");
    Path resolved = path.toAbsolutePath().normalize();
    ensureDefaultFile(resolved);
    whitelistPath = resolved;
    reloadInternal(resolved);
  }

  public static synchronized void reload() throws CommandWhitelistLoadException {
    Path path = whitelistPath;
    if (path == null) {
      throw new CommandWhitelistLoadException("Abomination configuration has not been initialized yet.");
    }
    reloadInternal(path);
  }

  public static boolean isCommandWhitelisted(String input) {
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

  public static boolean isPacketCapturesEnabled() {
    return packetCapturesEnabled;
  }

  private static void ensureDefaultFile(Path path) throws CommandWhitelistLoadException {
    if (Files.exists(path)) {
      if (!Files.isRegularFile(path)) {
        throw new CommandWhitelistLoadException(
            "Abomination configuration path " + path + " exists but is not a file.");
      }
      return;
    }

    Path parent = path.getParent();
    try {
      if (parent != null && Files.notExists(parent)) {
        Files.createDirectories(parent);
      }
      try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        writer.write("# Abomination Velocity configuration\n\n");
        writer.write("packet-captures:\n");
        writer.write("  enabled: false\n\n");
        writer.write("# Commands players are allowed to execute\n");
        writer.write("commands:\n");
        for (String command : DEFAULT_COMMANDS) {
          writer.write("  - " + command + "\n");
        }
      }
      LOGGER.info("Created default abomination configuration at {}", path);
    } catch (IOException e) {
      throw new CommandWhitelistLoadException("Unable to create default abomination configuration at "
          + path, e);
    }
  }

  private static void reloadInternal(Path path) throws CommandWhitelistLoadException {
    if (!Files.isRegularFile(path)) {
      throw new CommandWhitelistLoadException(
          "Abomination configuration path " + path + " does not point to a file.");
    }

    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      Yaml yaml = new Yaml();
      Object data = yaml.load(reader);

      LinkedHashSet<String> parsedCommands;
      boolean parsedPacketCapturesEnabled = false;

      if (data == null) {
        parsedCommands = new LinkedHashSet<>(DEFAULT_COMMANDS);
      } else if (data instanceof Iterable<?> iterable) {
        parsedCommands = parseCommands(iterable);
        LOGGER.warn("Abomination configuration at {} is using the deprecated list format.", path);
      } else if (data instanceof Map<?, ?> map) {
        parsedCommands = parseCommandsSection(map.get(COMMANDS_KEY));
        parsedPacketCapturesEnabled = parsePacketCapturesSection(map.get(PACKET_CAPTURES_KEY));
      } else {
        throw new CommandWhitelistLoadException(
            "Abomination configuration must be a YAML mapping or list.");
      }

      if (parsedCommands.isEmpty()) {
        throw new CommandWhitelistLoadException("Command whitelist is empty after parsing.");
      }

      commands = Collections.unmodifiableSet(parsedCommands);
      packetCapturesEnabled = parsedPacketCapturesEnabled;
      LOGGER.info(
          "Loaded {} whitelisted commands from {}; packet captures {}.",
          commands.size(),
          path,
          packetCapturesEnabled ? "enabled" : "disabled");
    } catch (IOException e) {
      throw new CommandWhitelistLoadException("Unable to read abomination configuration at " + path, e);
    }
  }

  private static LinkedHashSet<String> parseCommandsSection(Object commandsSection)
      throws CommandWhitelistLoadException {
    if (commandsSection == null) {
      return new LinkedHashSet<>(DEFAULT_COMMANDS);
    }
    if (!(commandsSection instanceof Iterable<?> iterable)) {
      throw new CommandWhitelistLoadException(
          "Abomination configuration field 'commands' must be a YAML list.");
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

  private static boolean parsePacketCapturesSection(Object packetCapturesSection)
      throws CommandWhitelistLoadException {
    if (packetCapturesSection == null) {
      return false;
    }
    if (packetCapturesSection instanceof Boolean enabled) {
      return enabled;
    }
    if (packetCapturesSection instanceof Map<?, ?> sectionMap) {
      Object enabledValue = sectionMap.get(PACKET_CAPTURES_ENABLED_KEY);
      if (enabledValue == null) {
        return false;
      }
      if (enabledValue instanceof Boolean enabled) {
        return enabled;
      }
      throw new CommandWhitelistLoadException(
          "Abomination configuration field 'packet-captures.enabled' must be a boolean.");
    }
    throw new CommandWhitelistLoadException(
        "Abomination configuration field 'packet-captures' must be a boolean or mapping.");
  }

  public static class CommandWhitelistLoadException extends Exception {
    CommandWhitelistLoadException(String message) {
      super(message);
    }

    CommandWhitelistLoadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
