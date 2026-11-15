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

package com.velocitypowered.proxy.connection.client;

import static com.google.common.net.UrlEscapers.urlFormParameterEscaper;
import static com.velocitypowered.proxy.VelocityServer.GENERAL_GSON;
import static com.velocitypowered.proxy.connection.VelocityConstants.EMPTY_BYTE_ARRAY;
import static com.velocitypowered.proxy.crypto.EncryptionUtils.decryptRsa;
import static com.velocitypowered.proxy.crypto.EncryptionUtils.generateServerId;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Longs;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.connection.PremiumAuthAttemptListener;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.crypto.IdentifiedKeyImpl;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.packet.EncryptionRequestPacket;
import com.velocitypowered.proxy.protocol.packet.EncryptionResponsePacket;
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerLoginPacket;
import com.velocitypowered.proxy.util.VelocityProperties;
import io.netty.buffer.ByteBuf;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Handles authenticating the player to Mojang's servers.
 */
public class InitialLoginSessionHandler implements MinecraftSessionHandler {

  private static final Logger logger = LogManager.getLogger(InitialLoginSessionHandler.class);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final String MOJANG_HASJOINED_URL =
      System.getProperty("mojang.sessionserver",
              "https://sessionserver.mojang.com/session/minecraft/hasJoined")
          .concat("?username=%s&serverId=%s");

  private final VelocityServer server;
  private final MinecraftConnection mcConnection;
  private final LoginInboundConnection inbound;
  private @MonotonicNonNull ServerLoginPacket login;
  private byte[] verify = EMPTY_BYTE_ARRAY;
  private LoginState currentState = LoginState.LOGIN_PACKET_EXPECTED;
  private final boolean forceKeyAuthentication;
  private @MonotonicNonNull PremiumAuthAttemptListener premiumAuthAttemptListener;
  private boolean trackPremiumAuthFailures;
  private @MonotonicNonNull PremiumAuthMessages premiumAuthMessages;
  private boolean failureRecorded;
  private long loginPacketReceivedNanos;
  private long encryptionStartedNanos;
  private long sessionCheckStartedNanos;
  private String premiumAuthLookup = "unknown";
  private String premiumAuthDecision = "unknown";

  InitialLoginSessionHandler(VelocityServer server, MinecraftConnection mcConnection,
                             LoginInboundConnection inbound) {
    this.server = Preconditions.checkNotNull(server, "server");
    this.mcConnection = Preconditions.checkNotNull(mcConnection, "mcConnection");
    this.inbound = Preconditions.checkNotNull(inbound, "inbound");
    this.forceKeyAuthentication = VelocityProperties.readBoolean(
            "auth.forceSecureProfiles", server.getConfiguration().isForceKeyAuthentication());
  }

  @Override
  public boolean handle(ServerLoginPacket packet) {
    assertState(LoginState.LOGIN_PACKET_EXPECTED);
    loginPacketReceivedNanos = System.nanoTime();
    this.currentState = LoginState.LOGIN_PACKET_RECEIVED;
    IdentifiedKey playerKey = packet.getPlayerKey();
    if (playerKey != null) {
      if (playerKey.hasExpired()) {
        inbound.disconnect(
            Component.translatable("multiplayer.disconnect.invalid_public_key_signature"));
        return true;
      }

      boolean isKeyValid;
      if (playerKey.getKeyRevision() == IdentifiedKey.Revision.LINKED_V2
          && playerKey instanceof final IdentifiedKeyImpl keyImpl) {
        isKeyValid = keyImpl.internalAddHolder(packet.getHolderUuid());
      } else {
        isKeyValid = playerKey.isSignatureValid();
      }

      if (!isKeyValid) {
        inbound.disconnect(Component.translatable("multiplayer.disconnect.invalid_public_key"));
        return true;
      }
    } else if (mcConnection.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19)
        && forceKeyAuthentication
        && mcConnection.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_19_3)) {
      inbound.disconnect(Component.translatable("multiplayer.disconnect.missing_public_key"));
      return true;
    }
    inbound.setPlayerKey(playerKey);
    this.login = packet;

    final PreLoginEvent event = new PreLoginEvent(inbound, login.getUsername(), login.getHolderUuid());
    server.getEventManager().fire(event).thenRunAsync(() -> {
      premiumAuthAttemptListener = event.getPremiumAuthAttemptListener();
      trackPremiumAuthFailures = event.shouldTrackPremiumAuthFailures();
      premiumAuthMessages = PremiumAuthMessages.from(event);
      premiumAuthLookup = event.getPremiumAuthLookup();
      premiumAuthDecision = event.getPremiumAuthDecision();
      PreLoginComponentResult result = event.getResult();
      boolean preLoginDenied = !shouldRecordPreLoginDisconnect(result);
      if (preLoginDenied) {
        failureRecorded = true;
      }

      if (mcConnection.isClosed()) {
        // The client may drop while plugins are making the pre-login decision.
        if (!preLoginDenied) {
          recordPremiumAuthFailure("prelogin_disconnect", false, "none", "none");
        }
        return;
      }

      Optional<Component> disconnectReason = result.getReasonComponent();
      if (disconnectReason.isPresent()) {
        // The component is guaranteed to be provided if the connection was denied.
        failureRecorded = true;
        inbound.disconnect(disconnectReason.get());
        return;
      }

      inbound.loginEventFired(() -> {
        if (mcConnection.isClosed()) {
          // The player was disconnected
          return;
        }

        mcConnection.eventLoop().execute(() -> {
          if (!result.isForceOfflineMode()
              && (server.getConfiguration().isOnlineMode() || result.isOnlineModeAllowed())) {
            // Request encryption.
            EncryptionRequestPacket request = generateEncryptionRequest();
            this.verify = Arrays.copyOf(request.getVerifyToken(), 4);
            mcConnection.write(request);
            encryptionStartedNanos = System.nanoTime();
            this.currentState = LoginState.ENCRYPTION_REQUEST_SENT;
          } else {
            mcConnection.setActiveSessionHandler(StateRegistry.LOGIN,
                new AuthSessionHandler(server, inbound,
                    GameProfile.forOfflinePlayer(login.getUsername()), false, null));
          }
        });
      });
    }, mcConnection.eventLoop()).exceptionally((ex) -> {
      logger.error("Exception in pre-login stage", ex);
      return null;
    });

    return true;
  }

  @Override
  public boolean handle(LoginPluginResponsePacket packet) {
    this.inbound.handleLoginPluginResponse(packet);
    return true;
  }

  @Override
  public boolean handle(EncryptionResponsePacket packet) {
    assertState(LoginState.ENCRYPTION_REQUEST_SENT);
    this.currentState = LoginState.ENCRYPTION_RESPONSE_RECEIVED;
    ServerLoginPacket login = this.login;
    if (login == null) {
      throw new IllegalStateException("No ServerLogin packet received yet.");
    }

    if (verify.length == 0) {
      throw new IllegalStateException("No EncryptionRequest packet sent yet.");
    }

    try {
      KeyPair serverKeyPair = server.getServerKeyPair();
      if (inbound.getIdentifiedKey() != null) {
        IdentifiedKey playerKey = inbound.getIdentifiedKey();
        if (!playerKey.verifyDataSignature(packet.getVerifyToken(), verify,
            Longs.toByteArray(packet.getSalt()))) {
          throw new IllegalStateException("Invalid client public signature.");
        }
      } else {
        byte[] decryptedVerifyToken = decryptRsa(serverKeyPair, packet.getVerifyToken());
        if (!MessageDigest.isEqual(verify, decryptedVerifyToken)) {
          throw new IllegalStateException("Unable to successfully decrypt the verification token.");
        }
      }

      byte[] decryptedSharedSecret = decryptRsa(serverKeyPair, packet.getSharedSecret());

      // Go ahead and enable encryption. Once the client sends EncryptionResponse, encryption
      // is enabled.
      mcConnection.enableEncryption(decryptedSharedSecret);

      String serverId = generateServerId(decryptedSharedSecret, serverKeyPair.getPublic());
      String playerIp = ((InetSocketAddress) mcConnection.getRemoteAddress()).getHostString();
      String url = String.format(MOJANG_HASJOINED_URL,
          urlFormParameterEscaper().escape(login.getUsername()), serverId);

      if (server.getConfiguration().shouldPreventClientProxyConnections()) {
        url += "&ip=" + urlFormParameterEscaper().escape(playerIp);
      }

      final String requestUrl = url;

      final HttpRequest httpRequest = HttpRequest.newBuilder()
              .setHeader("User-Agent",
                      server.getVersion().getName() + "/" + server.getVersion().getVersion())
              .timeout(Duration.ofSeconds(10))
              .uri(URI.create(requestUrl))
              .build();
      //noinspection resource
      final HttpClient httpClient = server.createHttpClient();
      currentState = LoginState.SESSION_CHECK_PENDING;
      sessionCheckStartedNanos = System.nanoTime();
      httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
          .whenCompleteAsync((response, throwable) -> {
            if (mcConnection.isClosed()) {
              // The player disconnected after we authenticated them.
              return;
            }

            if (throwable != null) {
              String httpClass = httpErrorClass(throwable);
              logger.error("Unable to authenticate player {} with Mojang session service ({})",
                  login.getUsername(), httpClass);
              failSessionCheck(httpClass);
              return;
            }

            if (response.statusCode() == 200) {
              currentState = LoginState.AUTHENTICATED;
              final GameProfile profile = GENERAL_GSON.fromJson(response.body(),
                  GameProfile.class);
              // Not so fast, now we verify the public key for 1.19.1+
              if (inbound.getIdentifiedKey() != null
                  && inbound.getIdentifiedKey().getKeyRevision() == IdentifiedKey.Revision.LINKED_V2
                  && inbound.getIdentifiedKey() instanceof final IdentifiedKeyImpl key) {
                if (!key.internalAddHolder(profile.getId())) {
                  inbound.disconnect(
                      Component.translatable("multiplayer.disconnect.invalid_public_key"));
                }
              }
              // All went well, initialize the session.
              mcConnection.setActiveSessionHandler(StateRegistry.LOGIN,
                  new AuthSessionHandler(server, inbound, profile, true, serverId));
            } else if (response.statusCode() == 204) {
              // Apparently an offline-mode user logged onto this online-mode proxy.
              Component failureMessage = premiumAuthMessages.failureMessage();
              recordPremiumAuthFailure("session_fail", failureMessage != null,
                  failureMessage != null ? "help" : "online_only", "204");
              inbound.disconnect(sessionFailureMessage(failureMessage));
            } else {
              // Something else went wrong
              String httpClass = httpStatusClass(response.statusCode());
              logger.error("Got an unexpected error class {} whilst contacting Mojang for login {}",
                  httpClass, login.getUsername());
              failSessionCheck(httpClass);
            }
          }, mcConnection.eventLoop())
          .whenComplete((ignored, throwable) -> {
            httpClient.close();
          });
    } catch (GeneralSecurityException e) {
      logger.error("Unable to enable encryption", e);
      mcConnection.close(true);
    }
    return true;
  }

  private EncryptionRequestPacket generateEncryptionRequest() {
    byte[] verify = new byte[4];
    SECURE_RANDOM.nextBytes(verify);

    EncryptionRequestPacket request = new EncryptionRequestPacket();
    request.setPublicKey(server.getServerKeyPair().getPublic().getEncoded());
    request.setVerifyToken(verify);
    return request;
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    mcConnection.close(true);
  }

  @Override
  public void disconnected() {
    String failureStage = failureStageForDisconnect(currentState);
    if (failureStage != null) {
      recordPremiumAuthFailure(failureStage, false, "none", "none");
    }
    this.inbound.cleanup();
  }

  private void failSessionCheck(String httpClass) {
    recordPremiumAuthFailure("auth_error", false, "authservers_down", httpClass);
    inbound.disconnect(Component.translatable("multiplayer.disconnect.authservers_down"));
  }

  static Component sessionFailureMessage(@Nullable Component failureMessage) {
    return failureMessage != null ? failureMessage
        : Component.translatable("velocity.error.online-mode-only", NamedTextColor.RED);
  }

  static @Nullable String failureStageForDisconnect(LoginState state) {
    return switch (state) {
      case LOGIN_PACKET_RECEIVED -> "prelogin_disconnect";
      case ENCRYPTION_REQUEST_SENT -> "encryption_disconnect";
      case ENCRYPTION_RESPONSE_RECEIVED -> "encryption_error";
      case SESSION_CHECK_PENDING -> "session_disconnect";
      default -> null;
    };
  }

  static boolean shouldRecordPreLoginDisconnect(PreLoginComponentResult result) {
    return result.getReasonComponent().isEmpty();
  }

  static void notifyFailureListener(PremiumAuthAttemptListener listener, String username, String stage,
      InetAddress remoteAddress, Instant occurredAt, boolean messageSent, String messageKind,
      long elapsedMs, long stageMs, String httpClass, String lookup, String decision,
      int protocolVersion) {
    try {
      listener.onFailure(username, stage, remoteAddress, occurredAt, messageSent, messageKind,
          elapsedMs, stageMs, httpClass, lookup, decision, protocolVersion);
    } catch (RuntimeException exception) {
      logger.warn("Premium authentication failure listener failed ({})", exception.getClass().getSimpleName());
    }
  }

  private void recordPremiumAuthFailure(String stage, boolean messageSent, String messageKind, String httpClass) {
    if (failureRecorded || !trackPremiumAuthFailures || premiumAuthAttemptListener == null
        || login == null) {
      return;
    }
    failureRecorded = true;
    InetAddress remoteAddress = inbound.getRemoteAddress().getAddress();
    long now = System.nanoTime();
    long stageStart = switch (stage) {
      case "session_fail", "auth_error", "session_disconnect" -> sessionCheckStartedNanos;
      case "encryption_disconnect", "encryption_error" -> encryptionStartedNanos;
      default -> loginPacketReceivedNanos;
    };
    notifyFailureListener(premiumAuthAttemptListener, login.getUsername(), stage, remoteAddress,
        Instant.now(), messageSent, messageKind,
        elapsedMillis(loginPacketReceivedNanos, now), elapsedMillis(stageStart, now), httpClass,
        premiumAuthLookup, premiumAuthDecision, mcConnection.getProtocolVersion().getProtocol());
  }

  private static long elapsedMillis(long started, long ended) {
    return started <= 0 || ended < started ? 0 : (ended - started) / 1_000_000L;
  }

  static String httpErrorClass(Throwable throwable) {
    boolean dns = false;
    boolean connection = false;
    boolean tls = false;
    Throwable cause = throwable;
    for (int depth = 0; cause != null && depth < 32; depth++) {
      if (cause instanceof HttpTimeoutException) {
        return "timeout";
      }
      dns |= cause instanceof UnknownHostException;
      connection |= cause instanceof ConnectException;
      tls |= cause instanceof javax.net.ssl.SSLException;
      Throwable next = cause.getCause();
      if (next == cause) {
        break;
      }
      cause = next;
    }
    if (dns) {
      return "dns";
    }
    if (connection) {
      return "conn";
    }
    if (tls) {
      return "tls";
    }
    return "io";
  }

  static String httpStatusClass(int statusCode) {
    if (statusCode == 429) {
      return "429";
    }
    if (statusCode >= 500 && statusCode <= 599) {
      return "5xx";
    }
    if (statusCode >= 400 && statusCode <= 499) {
      return "4xx";
    }
    return "io";
  }

  private void assertState(LoginState expectedState) {
    if (this.currentState != expectedState) {
      if (MinecraftDecoder.DEBUG) {
        logger.error("{} Received an unexpected packet requiring state {}, but we are in {}",
            inbound,
            expectedState, this.currentState);
      }
      mcConnection.close(true);
    }
  }

  record PremiumAuthMessages(@Nullable Component failureMessage) {

    static PremiumAuthMessages from(PreLoginEvent event) {
      return new PremiumAuthMessages(event.getPremiumAuthFailureMessage());
    }
  }

  enum LoginState {
    LOGIN_PACKET_EXPECTED,
    LOGIN_PACKET_RECEIVED,
    ENCRYPTION_REQUEST_SENT,
    ENCRYPTION_RESPONSE_RECEIVED,
    SESSION_CHECK_PENDING,
    AUTHENTICATED
  }
}
