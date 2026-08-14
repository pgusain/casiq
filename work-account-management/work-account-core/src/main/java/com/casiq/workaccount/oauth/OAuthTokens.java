package com.casiq.workaccount.oauth;

import java.time.Instant;

public record OAuthTokens(
        String accessToken,
        String refreshToken,
        Instant expiresAt,
        String scope,
        String tokenType) {
}
