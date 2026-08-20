package com.casiq.usermanagement.security;

import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class Hashes {
    private static final Logger LOG = Logger.getLogger(Hashes.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Hashes() {}

    public static String randomToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        LOG.debugf("Generated security token of length=%d", token.length());
        return token;
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            String hash = HexFormat.of().formatHex(digest);
            LOG.debugf("Computed SHA-256 hash length=%d", hash.length());
            return hash;
        } catch (NoSuchAlgorithmException exception) {
            LOG.error("SHA-256 is unavailable for hashing", exception);
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
