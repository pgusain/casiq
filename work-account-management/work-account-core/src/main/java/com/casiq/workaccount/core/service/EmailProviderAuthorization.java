package com.casiq.workaccount.core.service;

import java.time.Instant;

public interface EmailProviderAuthorization {
    String providerCode();

    AuthorizationResponse beginAuthorization(Long workAccountId, String loginHint);

    record AuthorizationResponse(String authorizationUrl, Instant expiresAt) {
    }
}
