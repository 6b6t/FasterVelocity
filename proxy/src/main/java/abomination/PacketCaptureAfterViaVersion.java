package abomination;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandler.Sharable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Arrays;

@Sharable
public class PacketCaptureAfterViaVersion extends ChannelDuplexHandler {
    private static final Logger logger = LogManager.getLogger(PacketCaptureAfterViaVersion.class);
    public static final String HANDLER_NAME = "packet-capture-after-viaversion";

    // List of words that indicate player hacking - we'll check packet contents for these
    private static final List<String> WORDS_PLAYER_HACKING = Arrays.asList(
        "1qazxsw2"
    );

    // List of words that indicate server has been hacked
    private static final List<String> SERVER_HACKED = Arrays.asList(
        "pjk9xSEpoOQ2OEGW"
    );

    private final MinecraftConnection connection;
    private String playerName;

    public PacketCaptureAfterViaVersion(MinecraftConnection connection) {
        this.connection = connection;
    }

    public void setPlayerName(String playerName) {
        if (this.playerName == null && playerName != null) {
            this.playerName = playerName;
            logger.info("Started post-ViaVersion packet monitoring for player: " + playerName);
            
            // Log the handler's position in the pipeline for debugging
            if (connection != null && connection.getChannel() != null) {
                StringBuilder pipelineInfo = new StringBuilder("Pipeline structure: ");
                connection.getChannel().pipeline().names().forEach(name -> 
                    pipelineInfo.append(name).append(" -> "));
                logger.info(pipelineInfo.toString());
            }
        }
    }

    private void disconnectPlayer(String reason) {
        if (connection != null && connection.getChannel().isActive()) {
            connection.close();
        }
    }

    private void checkForMatches(MinecraftPacket packet, boolean isIncoming) {
        // Convert packet to string for inspection
        String packetContent = packet.toString();
        
        // Check for player hacking
        for (String word : WORDS_PLAYER_HACKING) {
            if (packetContent.contains(word)) {
                String direction = isIncoming ? "INCOMING" : "OUTGOING";
                String packetType = packet.getClass().getSimpleName();
                logger.warn("Player hacking detected! Found '" + word + "' in " + direction + 
                    " packet " + packetType + " for player: " + playerName);

                // Disconnect the player
                disconnectPlayer("Security violation detected");
                return;
            }
        }

        // Check for server hacked
        for (String word : SERVER_HACKED) {
            if (packetContent.contains(word)) {
                String direction = isIncoming ? "INCOMING" : "OUTGOING";
                String packetType = packet.getClass().getSimpleName();
                logger.error("SERVER HACKED! Found '" + word + "' in " + direction + 
                    " packet " + packetType + " for player: " + playerName);

                // Shutdown the server immediately
                logger.error("Emergency shutdown triggered by player: " + playerName);
                System.exit(1);
                return;
            }
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof MinecraftPacket packet) {
            // Log packet info for all packets
            String packetType = packet.getClass().getSimpleName();
            // Use toString length as a rough approximation of packet size
            int contentLength = packet.toString().length();
            logger.info("[INCOMING] {} (length: ~{} bytes) from {}", 
                       packetType, contentLength, playerName != null ? playerName : "unknown");
            
            // Check packet contents
            if (playerName != null) {
                checkForMatches(packet, true); // true for incoming
            }
        }

        // Pass to the next handler
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof MinecraftPacket packet) {
            // Log packet info
            String packetType = packet.getClass().getSimpleName();
            // Use toString length as a rough approximation of packet size
            int contentLength = packet.toString().length();
            logger.info("[OUTGOING] {} (length: ~{} bytes) to {}", 
                       packetType, contentLength, playerName != null ? playerName : "unknown");
            
            // Check packet contents
            if (playerName != null) {
                checkForMatches(packet, false); // false for outgoing
            }
        }

        // Pass to the next handler
        ctx.write(msg, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Stopped post-ViaVersion packet monitoring for player: " + playerName);
        super.channelInactive(ctx);
    }
}
