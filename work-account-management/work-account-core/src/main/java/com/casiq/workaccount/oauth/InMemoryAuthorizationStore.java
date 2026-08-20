package com.casiq.workaccount.oauth;

import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAuthorizationStore {
    private static final Logger LOG = Logger.getLogger(InMemoryAuthorizationStore.class);

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Duration lifetime;
    private final Clock clock;

    public InMemoryAuthorizationStore(Duration lifetime) {
        this(lifetime, Clock.systemUTC());
    }

    InMemoryAuthorizationStore(Duration lifetime, Clock clock) {
        this.lifetime = lifetime;
        this.clock = clock;
    }

    public Instant put(String state, String codeVerifier) {
        return put(state, codeVerifier, null);
    }

    public Instant put(String state, String codeVerifier, Long workAccountId) {
        Instant expiresAt = clock.instant().plus(lifetime);
        attempts.put(state, new Attempt(codeVerifier, workAccountId, expiresAt));
        attempts.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(clock.instant()));
        LOG.debugf("Stored OAuth state metadata state=%s workAccountId=%s expiresAt=%s", state, workAccountId, expiresAt);
        return expiresAt;
    }

    public Optional<AuthorizationAttempt> consume(String state) {
        if (state == null) return Optional.empty();
        Attempt attempt = attempts.remove(state);
        if (attempt == null || !attempt.expiresAt().isAfter(clock.instant())) {
            LOG.warnf("OAuth state was missing or expired state=%s", state);
            return Optional.empty();
        }
        LOG.debugf("Consumed OAuth state state=%s workAccountId=%s", state, attempt.workAccountId());
        return Optional.of(new AuthorizationAttempt(attempt.codeVerifier(), attempt.workAccountId()));
    }

    public record AuthorizationAttempt(String codeVerifier, Long workAccountId) {}

    private record Attempt(String codeVerifier, Long workAccountId, Instant expiresAt) {}
}
