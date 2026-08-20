package com.casiq.workaccount.oauth;

import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class Pkce {
    private static final Logger LOG = Logger.getLogger(Pkce.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Pkce() {}

    public static PkceAuthorization create() {
        String state = randomUrlSafe(32);
        String verifier = randomUrlSafe(64);
        LOG.debugf("Created PKCE state=%s verifierLength=%d", state, verifier.length());
        return new PkceAuthorization(state, verifier, challenge(verifier));
    }

    public static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            LOG.error("SHA-256 is unavailable for PKCE challenge generation", e);
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
