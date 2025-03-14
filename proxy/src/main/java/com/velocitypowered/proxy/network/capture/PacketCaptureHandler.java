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

import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

public class PacketCaptureHandler extends ChannelDuplexHandler {
    private static final String HANDLER_NAME = "velocity-packet-capture";

    private final PacketCaptureManager manager;
    private final VelocityServerConnection connection;

    public PacketCaptureHandler(PacketCaptureManager manager, VelocityServerConnection connection) {
        this.manager = manager;
        this.connection = connection;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (manager.isEnabled() && msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            // Make sure to retain the buffer as we're reading it but not consuming it
            buf.retain();
            manager.captureClientBound(connection, buf);
            buf.release(); // Release our reference
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (manager.isEnabled() && msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            buf.retain();
            manager.captureServerBound(connection, buf);
            buf.release();
        }
        super.write(ctx, msg, promise);
    }

    public static String name() {
        return HANDLER_NAME;
    }
}
