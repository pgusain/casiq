package com.casiq.workaccount.oauth;

import org.jboss.logging.Logger;

import java.time.Instant;

public record OAuthTokens(
        String accessToken,
        String refreshToken,
        Instant expiresAt,
        String scope,
        String tokenType) {
    private static final Logger LOG = Logger.getLogger(OAuthTokens.class);

    public OAuthTokens {
        LOG.debugf("Created OAuth token metadata expiresAt=%s scope=%s tokenType=%s", expiresAt, scope, tokenType);
    }
}
