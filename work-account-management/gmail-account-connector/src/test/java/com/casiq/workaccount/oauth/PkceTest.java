package com.casiq.workaccount.oauth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PkceTest {
    @Test
    void createsUniqueS256AuthorizationValues() {
        var first = Pkce.create();
        var second = Pkce.create();
        assertNotEquals(first.state(), second.state());
        assertNotEquals(first.codeVerifier(), second.codeVerifier());
        assertEquals(Pkce.challenge(first.codeVerifier()), first.codeChallenge());
    }

    @Test
    void authorizationStateCanOnlyBeConsumedOnce() {
        var store = new InMemoryAuthorizationStore(Duration.ofMinutes(10));
        store.put("state", "verifier");
        assertEquals("verifier", store.consume("state").orElseThrow().codeVerifier());
        assertFalse(store.consume("state").isPresent());
        assertTrue(store.consume(null).isEmpty());
    }
}
