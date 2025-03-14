package abomination;

import com.velocitypowered.proxy.connection.MinecraftConnection;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.io.File;
import java.io.IOException;
import net.kyori.adventure.text.Component;

@Sharable
public class PacketStreamCapture extends ChannelDuplexHandler {
    private static final Logger logger = LogManager.getLogger(PacketStreamCapture.class);

    // List of words that indicate player hacking
    private static final List<String> WORDS_PLAYER_HACKING = Arrays.asList(
        "ThisIsTotallyNotABackdoorPlugin"
    );

    // List of words that indicate server has been hacked
    private static final List<String> SERVER_HACKED = Arrays.asList(
//        "v4590mivsxme90vmiksvjx8cvu94nnjndkjfnvu3",
//        "4bvs4bs5b5gffjzxcgr"
        "pjk9xSEpoOQ2OEGW"
    );

    private final MinecraftConnection connection;
    private String playerName;

    // Buffer for incomplete matches (data might be split across multiple packets)
    private StringBuilder incomingBuffer = new StringBuilder();
    private StringBuilder outgoingBuffer = new StringBuilder();
    private static final int MAX_BUFFER_SIZE = 1024000; // Limit buffer size to prevent memory issues

    // Counters for tracking bytes
    private long totalIncomingBytes = 0;
    private long totalOutgoingBytes = 0;

    public PacketStreamCapture(MinecraftConnection connection) {
        this.connection = connection;
    }

    public void setPlayerName(String playerName) {
        if (this.playerName == null && playerName != null) {
            this.playerName = playerName;
            logger.info("Started packet monitoring for player: " + playerName);
        }
    }

    private void disconnectPlayer(String reason) {
        if (connection != null && connection.getChannel().isActive()) {
            connection.close();
        }
    }

    private void checkForMatches(ByteBuf buf, boolean isIncoming) {
        // Use the correct buffer based on direction
        StringBuilder buffer = isIncoming ? incomingBuffer : outgoingBuffer;

        // Convert the ByteBuf to a string
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        String content = new String(bytes, StandardCharsets.UTF_8);

        // Add new content to buffer
        buffer.append(content);

        // Trim buffer if it gets too large
        if (buffer.length() > MAX_BUFFER_SIZE) {
            buffer.delete(0, buffer.length() - MAX_BUFFER_SIZE);
        }

        // Check for matches
        String bufferStr = buffer.toString();

        // Check for player hacking
        for (String word : WORDS_PLAYER_HACKING) {
            if (bufferStr.contains(word)) {
                String direction = isIncoming ? "INCOMING" : "OUTGOING";
                logger.warn("Player hacking detected! Found '" + word + "' in " + direction + " data for player: " + playerName);

                // Disconnect the player
                disconnectPlayer("Security violation detected");
                return;
            }
        }

        // Check for server hacked
        for (String word : SERVER_HACKED) {
            if (bufferStr.contains(word)) {
                String direction = isIncoming ? "INCOMING" : "OUTGOING";
                logger.error("SERVER HACKED! Found '" + word + "' in " + direction + " data for player: " + playerName);

                // Create a hacked file
                try {
                    File hackedFile = new File("hacked");
                    hackedFile.createNewFile();
                    logger.error("Created 'hacked' file marker");
                } catch (IOException e) {
                    logger.error("Failed to create 'hacked' file", e);
                }

                // Shutdown the server immediately
                logger.error("Emergency shutdown triggered by player: " + playerName);
                System.exit(1);
                return;
            }
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (playerName != null && msg instanceof ByteBuf buf) {
            int bytes = buf.readableBytes();
            totalIncomingBytes += bytes;
            checkForMatches(buf, true); // true for incoming
        }

        // Pass to the next handler
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (playerName != null && msg instanceof ByteBuf buf) {
            int bytes = buf.readableBytes();
            totalOutgoingBytes += bytes;
            checkForMatches(buf, false); // false for outgoing
        }

        // Pass to the next handler
        ctx.write(msg, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Clear buffers when connection closes
        incomingBuffer.setLength(0);
        outgoingBuffer.setLength(0);

        logger.info("Stopped packet monitoring for player: " + playerName +
                    ". Total bytes received: " + totalIncomingBytes +
                    ", total bytes sent: " + totalOutgoingBytes);

        super.channelInactive(ctx);
    }

    public static final String HANDLER_NAME = "packet-stream-capture";
}
