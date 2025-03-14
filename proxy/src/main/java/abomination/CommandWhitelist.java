package abomination;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CommandWhitelist {
    private static final Set<String> COMMANDS = new HashSet<>(Arrays.asList(
        "register",
        "reg",
        "unregister",
        "login",
        "l",
        "email",
        "changepassword",
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
        "l",
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
        "skin",
        "skins",
        "summon",
//        "execute",
//        "tp",
//        "gamemode",
        "give",
        "chatcolor",
        "christmas",
        "balloons",
        "balloon",
        "sit",
//        "commandwhitelist",
        "link"
    ));

    public static boolean isCommandWhitelisted(String input) {
        String commandWithoutSlash = input;
        if (input.startsWith("/")) {
            commandWithoutSlash = input.substring(1);
        }
        int spaceIndex = commandWithoutSlash.indexOf(' ');
        if (spaceIndex == -1) {
            // If there's no space, check the whole command
            return COMMANDS.contains(commandWithoutSlash);
        } else {
            // Check only the command name (up to the first space)
            String commandName = commandWithoutSlash.substring(0, spaceIndex);
            return COMMANDS.contains(commandName);
        }
    }
}
