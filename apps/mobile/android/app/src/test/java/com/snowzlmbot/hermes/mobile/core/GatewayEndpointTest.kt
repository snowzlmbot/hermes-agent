package com.snowzlmbot.hermes.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayEndpointTest {
  @Test
  fun normalizesHttpsOriginAndPreservesReverseProxyBasePath() {
    val endpoint = GatewayEndpoint.parse("  https://agent.example/hermes/  ")

    assertEquals("https://agent.example/hermes/", endpoint.httpBaseUrl.toString())
    assertEquals("wss://agent.example/hermes/api/ws", endpoint.webSocketUrl().toString())
  }

  @Test
  fun defaultsBareHostsToHttpsAndRejectsUnsupportedSchemes() {
    assertEquals(
      "https://agent.example/",
      GatewayEndpoint.parse("agent.example").httpBaseUrl.toString(),
    )

    assertThrows(EndpointValidationException::class.java) {
      GatewayEndpoint.parse("ftp://agent.example")
    }
  }

  @Test
  fun cleartextRemoteHostsRequireExplicitConsentButLoopbackDoesNot() {
    assertThrows(InsecureEndpointException::class.java) {
      GatewayEndpoint.parse("http://192.168.1.7:8080")
    }

    assertEquals(
      "ws://192.168.1.7:8080/api/ws",
      GatewayEndpoint.parse("http://192.168.1.7:8080", allowInsecure = true).webSocketUrl().toString(),
    )
    assertEquals(
      "ws://127.0.0.1:8080/api/ws",
      GatewayEndpoint.parse("http://127.0.0.1:8080").webSocketUrl().toString(),
    )
  }

  @Test
  fun authValuesAreEncodedAndNeverIncludedInCredentialText() {
    val endpoint = GatewayEndpoint.parse("https://agent.example")
    val token = GatewayCredential.Token(SecretValue("a+b /?"))
    val ticket = GatewayCredential.Ticket(SecretValue("single use"))

    assertEquals("a%2Bb%20%2F%3F", endpoint.webSocketUrl(token).encodedQuery?.substringAfter("token="))
    assertEquals("single use", endpoint.webSocketUrl(ticket).queryParameter("ticket"))
    assertFalse(token.toString().contains("a+b"))
    assertFalse(ticket.toString().contains("single use"))
    assertTrue(token.toString().contains("REDACTED"))
  }
}