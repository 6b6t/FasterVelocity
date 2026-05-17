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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandWhitelistTest {

  @TempDir
  Path tempDirectory;

  @Test
  void generatedConfigEnablesCommandWhitelist() throws Exception {
    Path config = tempDirectory.resolve("fastervelocity.yml");

    CommandWhitelist.initialize(config);

    assertTrue(Files.readString(config).contains("command-whitelist-enabled: true"));
    assertTrue(CommandWhitelist.isCommandWhitelisted("server"));
    assertFalse(CommandWhitelist.isCommandWhitelisted("not-whitelisted"));
  }

  @Test
  void missingSettingDefaultsToEnabled() throws Exception {
    Path config = writeConfig("""
        commands:
          - allowed
        plugin-channels:
          - minecraft:brand
        """);

    CommandWhitelist.initialize(config);

    assertTrue(CommandWhitelist.isCommandWhitelisted("/allowed argument"));
    assertFalse(CommandWhitelist.isCommandWhitelisted("/blocked argument"));
  }

  @Test
  void disabledCommandWhitelistAllowsAllCommands() throws Exception {
    Path config = writeConfig("""
        command-whitelist-enabled: false
        commands:
          - allowed
        plugin-channels:
          - minecraft:brand
        """);

    CommandWhitelist.initialize(config);

    assertTrue(CommandWhitelist.isCommandWhitelisted("/allowed argument"));
    assertTrue(CommandWhitelist.isCommandWhitelisted("/blocked argument"));
    assertFalse(CommandWhitelist.isPluginChannelWhitelisted("blocked:channel"));
  }

  @Test
  void reloadUpdatesCommandWhitelistSetting() throws Exception {
    Path config = writeConfig("""
        command-whitelist-enabled: false
        commands:
          - allowed
        """);
    CommandWhitelist.initialize(config);
    assertTrue(CommandWhitelist.isCommandWhitelisted("blocked"));

    Files.writeString(config, """
        command-whitelist-enabled: true
        commands:
          - allowed
        """);
    CommandWhitelist.reload();

    assertFalse(CommandWhitelist.isCommandWhitelisted("blocked"));
  }

  @Test
  void rejectsNonBooleanCommandWhitelistSetting() throws IOException {
    Path config = writeConfig("""
        command-whitelist-enabled: disabled
        commands:
          - allowed
        """);

    assertThrows(
        CommandWhitelist.CommandWhitelistLoadException.class,
        () -> CommandWhitelist.initialize(config));
  }

  private Path writeConfig(String contents) throws IOException {
    Path config = tempDirectory.resolve("fastervelocity.yml");
    Files.writeString(config, contents);
    return config;
  }
}
