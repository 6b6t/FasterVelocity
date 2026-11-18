/*
 * Copyright (C) 2018-2023 Velocity Contributors
 *
 * The Velocity API is licensed under the terms of the MIT License. For more details,
 * reference the LICENSE file in the api top-level directory.
 */

package com.velocitypowered.api.event.connection;

import java.net.InetAddress;
import java.time.Instant;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Receives failed premium authentication attempt callbacks for proxy plugins that track them.
 *
 * <p>The address is supplied in memory only. Implementations must not persist or log it directly.
 * The proxy does not retain this listener beyond the login attempt.</p>
 */
public interface PremiumAuthAttemptListener {

  /** Called when a marked premium authentication attempt fails. */
  void onFailure(String username, String stage, @Nullable InetAddress remoteAddress,
      Instant occurredAt, boolean messageSent, String messageKind, long elapsedMs, long stageMs,
      String httpClass, String lookup, String decision, int protocolVersion);
}
