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

  private static volatile Set<String> commands = Set.copyOf(DEFAULT_COMMANDS);
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
      throw new CommandWhitelistLoadException("Command whitelist has not been initialized yet.");
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

  private static void ensureDefaultFile(Path path) throws CommandWhitelistLoadException {
    if (Files.exists(path)) {
      if (!Files.isRegularFile(path)) {
        throw new CommandWhitelistLoadException(
            "Command whitelist path " + path + " exists but is not a file.");
      }
      return;
    }

    Path parent = path.getParent();
    try {
      if (parent != null && Files.notExists(parent)) {
        Files.createDirectories(parent);
      }
      Yaml yaml = new Yaml();
      String content = yaml.dump(DEFAULT_COMMANDS);
      try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        writer.write("# Commands players are allowed to execute\n");
        writer.write(content);
      }
      LOGGER.info("Created default command whitelist at {}", path);
    } catch (IOException e) {
      throw new CommandWhitelistLoadException("Unable to create default command whitelist at "
          + path, e);
    }
  }

  private static void reloadInternal(Path path) throws CommandWhitelistLoadException {
    if (!Files.isRegularFile(path)) {
      throw new CommandWhitelistLoadException(
          "Command whitelist path " + path + " does not point to a file.");
    }

    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      Yaml yaml = new Yaml();
      Object data = yaml.load(reader);
      if (!(data instanceof Iterable<?> iterable)) {
        throw new CommandWhitelistLoadException(
            "Command whitelist must be a YAML list of command names.");
      }

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

      if (parsedCommands.isEmpty()) {
        throw new CommandWhitelistLoadException("Command whitelist is empty after parsing.");
      }

      commands = Collections.unmodifiableSet(Set.copyOf(parsedCommands));
      LOGGER.info("Loaded {} whitelisted commands from {}", commands.size(), path);
    } catch (IOException e) {
      throw new CommandWhitelistLoadException("Unable to read command whitelist at " + path, e);
    }
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

