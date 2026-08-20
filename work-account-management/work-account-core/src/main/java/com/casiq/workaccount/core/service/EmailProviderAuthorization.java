package com.casiq.workaccount.core.service;

import org.jboss.logging.Logger;

import java.time.Instant;

public interface EmailProviderAuthorization {
    Logger LOG = Logger.getLogger(EmailProviderAuthorization.class);

    String providerCode();

    AuthorizationResponse beginAuthorization(Long workAccountId, String loginHint);

    record AuthorizationResponse(String authorizationUrl, Instant expiresAt) {
    }
}
