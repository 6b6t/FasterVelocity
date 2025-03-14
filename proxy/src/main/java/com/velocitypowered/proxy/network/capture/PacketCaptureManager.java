/*
 * Copyright (C) 2018-2023 Velocity Contributors
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

package com.velocitypowered.proxy.network.capture;

import com.github.luben.zstd.ZstdOutputStream;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import io.netty.buffer.ByteBuf;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PacketCaptureManager {

    public enum PacketDirection {
        SERVER_TO_CLIENT(0),  // S2C
        CLIENT_TO_SERVER(1);  // C2S

        private final byte value;

        PacketDirection(int value) {
            this.value = (byte) value;
        }

        public byte getValue() {
            return value;
        }
    }
    private static final Logger logger = LogManager.getLogger(PacketCaptureManager.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Path captureDirectory;
    private final boolean enabled;
    private final Map<String, ZstdOutputStream> activeCaptures = new ConcurrentHashMap<>();

    public PacketCaptureManager(Path captureDirectory, boolean enabled) {
        this.captureDirectory = captureDirectory;
        this.enabled = enabled;

        try {
            Files.createDirectories(captureDirectory);
            if (enabled) {
                logger.info("Packet capture enabled, saving to {}", captureDirectory.toAbsolutePath());
            } else {
                logger.debug("Created packet capture directory at {}", captureDirectory.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Failed to create packet capture directory", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void captureClientBound(VelocityServerConnection connection, ByteBuf data) {
        if (!enabled) return;
        String id = getCaptureId(connection);
        capture(id, data, PacketDirection.SERVER_TO_CLIENT);
    }

    public void captureServerBound(VelocityServerConnection connection, ByteBuf data) {
        if (!enabled) return;
        String id = getCaptureId(connection);
        capture(id, data, PacketDirection.CLIENT_TO_SERVER);
    }

    private String getCaptureId(VelocityServerConnection connection) {
        return connection.getPlayer().getUsername() + "_" +
               connection.getServerInfo().getName();
    }

    private void capture(String id, ByteBuf data, PacketDirection direction) {
        try {
            ZstdOutputStream out = activeCaptures.computeIfAbsent(id, this::createCaptureFile);
            if (out == null) return;

            // Prepare adjusted values for SERVER_TO_CLIENT packets
            int bytesToWrite = data.readableBytes();
            int readerIndex = data.readerIndex();

            // Skip first byte for SERVER_TO_CLIENT packets
            if (direction == PacketDirection.SERVER_TO_CLIENT && bytesToWrite > 0) {
                readerIndex++;
                bytesToWrite--;
            }
            if (direction == PacketDirection.CLIENT_TO_SERVER && bytesToWrite > 1) {
                readerIndex += 2;
                bytesToWrite -= 2;
            }

            // Write packet header: [timestamp(8) | direction(1) | length(4)]
            byte[] headerBytes = new byte[13];
            ByteBuffer headerBuffer = ByteBuffer.wrap(headerBytes);
            headerBuffer.putLong(System.currentTimeMillis());
            headerBuffer.put(direction.getValue());
            headerBuffer.putInt(bytesToWrite);
            out.write(headerBytes);

            // Write packet data all at once
            if (bytesToWrite > 0) {
                byte[] packetData = new byte[bytesToWrite];
                data.getBytes(readerIndex, packetData, 0, bytesToWrite);
                out.write(packetData);
            }

        } catch (IOException e) {
            logger.error("Failed to capture packet for {}", id, e);
            closeCapture(id);
        }
    }

    private ZstdOutputStream createCaptureFile(String id) {
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            Path captureFile = captureDirectory.resolve(timestamp + "_" + id + ".tcpdump.zst");
            OutputStream fileOut = Files.newOutputStream(captureFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            // Add buffering to improve compression efficiency
            BufferedOutputStream bufferedOut = new BufferedOutputStream(fileOut);

            // Create zstd output stream with default compression level
            return new ZstdOutputStream(bufferedOut, -1, false, true);
        } catch (IOException e) {
            logger.error("Failed to create capture file for {}", id, e);
            return null;
        }
    }

    public void closeCapture(String id) {
        ZstdOutputStream out = activeCaptures.remove(id);
        if (out != null) {
            try {
                out.close(); // This will also flush and finish the compression stream
                logger.debug("Closed packet capture for {}", id);
            } catch (IOException e) {
                logger.error("Error closing capture file for {}", id, e);
            }
        }
    }

    public void stopCapture(VelocityServerConnection connection) {
        if (connection != null) {
            closeCapture(getCaptureId(connection));
        }
    }

    public void shutdown() {
        for (Map.Entry<String, ZstdOutputStream> entry : activeCaptures.entrySet()) {
            try {
                entry.getValue().close();
                logger.debug("Closed packet capture for {} during shutdown", entry.getKey());
            } catch (IOException e) {
                logger.error("Error closing capture file during shutdown", e);
            }
        }
        activeCaptures.clear();
    }
}
