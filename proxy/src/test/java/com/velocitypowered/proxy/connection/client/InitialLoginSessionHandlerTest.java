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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PremiumAuthAttemptListener;
import com.velocitypowered.api.proxy.InboundConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

class InitialLoginSessionHandlerTest {

  @Test
  void capturesOnlyTheSessionFailureMessage() {
    PreLoginEvent event = new PreLoginEvent(mock(InboundConnection.class), "ExampleName", null);
    Component failure = Component.text("premium failure");
    event.setPremiumAuthFailureMessage(failure);

    InitialLoginSessionHandler.PremiumAuthMessages captured =
        InitialLoginSessionHandler.PremiumAuthMessages.from(event);

    assertSame(failure, captured.failureMessage());
  }

  @Test
  void preloginCannotRejectBeforeCurrentSessionCheck() {
    assertThrows(NoSuchMethodException.class,
        () -> PreLoginEvent.class.getMethod("setPremiumAuthRetryMessage", Component.class));
  }

  @Test
  void sanitizesPremiumAuthContextLabels() {
    PreLoginEvent event = new PreLoginEvent(mock(InboundConnection.class), "ExampleName", null);
    event.setPremiumAuthContext("fresh_premium", "premium");
    assertEquals("fresh_premium", event.getPremiumAuthLookup());
    assertEquals("premium", event.getPremiumAuthDecision());

    event.setPremiumAuthContext("bad label with spaces", "paid name");
    assertEquals("unknown", event.getPremiumAuthLookup());
    assertEquals("unknown", event.getPremiumAuthDecision());
  }

  @Test
  void reportsPremiumCacheDenialsThroughTheLifecycleListener() throws Exception {
    InboundConnection connection = mock(InboundConnection.class);
    when(connection.getRemoteAddress()).thenReturn(
        new InetSocketAddress(InetAddress.getByAddress(new byte[] {1, 2, 3, 4}), 25565));
    PreLoginEvent event = new PreLoginEvent(connection, "ExampleName", null);
    String[] stage = new String[1];
    String[] capturedMessageKind = new String[1];
    boolean[] capturedMessageSent = new boolean[1];
    event.setPremiumAuthAttemptListener(new PremiumAuthAttemptListener() {
      @Override
      public void onFailure(String username, String failureStage, InetAddress remoteAddress,
          Instant occurredAt, boolean messageSent, String messageKind, long elapsedMs, long stageMs,
          String httpClass, String lookup, String decision, int protocolVersion) {
        stage[0] = failureStage;
        capturedMessageKind[0] = messageKind;
        capturedMessageSent[0] = messageSent;
      }

      @Override
      public void onSuccess(String username, InetAddress remoteAddress, Instant joinedAt) {
      }
    }, true);

    event.recordPremiumAuthCacheDenial();

    assertEquals("cache_denied", stage[0]);
    assertEquals("use_online", capturedMessageKind[0]);
    assertTrue(capturedMessageSent[0]);
  }

  @Test
  void sessionFailureUsesPremiumHelpWhenProvidedAndDefaultOtherwise() {
    Component help = Component.text("premium failure");

    assertSame(help, InitialLoginSessionHandler.sessionFailureMessage(help));
    assertEquals(
        Component.translatable("velocity.error.online-mode-only", NamedTextColor.RED),
        InitialLoginSessionHandler.sessionFailureMessage(null));
  }

  @Test
  void mapsDisconnectStatesToTheirFailureStages() {
    assertEquals("prelogin_disconnect", InitialLoginSessionHandler.failureStageForDisconnect(
        InitialLoginSessionHandler.LoginState.LOGIN_PACKET_RECEIVED));
    assertEquals("encryption_disconnect", InitialLoginSessionHandler.failureStageForDisconnect(
        InitialLoginSessionHandler.LoginState.ENCRYPTION_REQUEST_SENT));
    assertEquals("encryption_error", InitialLoginSessionHandler.failureStageForDisconnect(
        InitialLoginSessionHandler.LoginState.ENCRYPTION_RESPONSE_RECEIVED));
    assertEquals("session_disconnect", InitialLoginSessionHandler.failureStageForDisconnect(
        InitialLoginSessionHandler.LoginState.SESSION_CHECK_PENDING));
  }

  @Test
  void preloginDenialsAreNotCountedAsPremiumDisconnects() {
    PreLoginEvent allowed = new PreLoginEvent(mock(InboundConnection.class), "ExampleName", null);
    PreLoginEvent denied = new PreLoginEvent(mock(InboundConnection.class), "ExampleName", null);
    denied.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text("denied")));

    assertTrue(InitialLoginSessionHandler.shouldRecordPreLoginDisconnect(allowed.getResult()));
    assertFalse(InitialLoginSessionHandler.shouldRecordPreLoginDisconnect(denied.getResult()));
  }

  @Test
  void classifiesSessionServerStatusesAndTransportFailures() {
    assertEquals("429", InitialLoginSessionHandler.httpStatusClass(429));
    assertEquals("4xx", InitialLoginSessionHandler.httpStatusClass(403));
    assertEquals("5xx", InitialLoginSessionHandler.httpStatusClass(503));
    assertEquals("timeout", InitialLoginSessionHandler.httpErrorClass(new HttpTimeoutException("timeout")));
    assertEquals("dns", InitialLoginSessionHandler.httpErrorClass(new UnknownHostException("host")));
  }

  @Test
  void listenerExceptionsAreSwallowed() {
    PremiumAuthAttemptListener listener = new PremiumAuthAttemptListener() {
      @Override
      public void onFailure(String username, String stage, java.net.InetAddress remoteAddress,
          Instant occurredAt, boolean messageSent, String messageKind, long elapsedMs, long stageMs,
          String httpClass, String lookup, String decision, int protocolVersion) {
        throw new IllegalStateException("ignored listener detail");
      }

      @Override
      public void onSuccess(String username, java.net.InetAddress remoteAddress, Instant joinedAt) {
        throw new IllegalStateException("ignored listener detail");
      }
    };

    assertDoesNotThrow(() -> InitialLoginSessionHandler.notifyFailureListener(
        listener, "ExampleName", "session_fail", null, Instant.EPOCH, false, "online_only",
        10, 5, "204", "fresh_premium", "premium", 769));
    assertDoesNotThrow(() -> AuthSessionHandler.notifyPremiumAuthSuccess(
        listener, "ExampleName", null, Instant.EPOCH));
  }
}
