package com.casiq.workaccount.gmail;

import com.casiq.workaccount.oauth.InMemoryAuthorizationStore;
import com.casiq.workaccount.oauth.OAuthTokens;
import com.casiq.workaccount.oauth.Pkce;
import com.casiq.workaccount.core.api.WorkAccountView;
import com.casiq.workaccount.core.service.WorkAccountService;
import com.casiq.workaccount.core.service.EmailProviderAuthorization;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class GmailOAuthService implements EmailProviderAuthorization {
    private static final String SCOPE =
            "openid email profile https://www.googleapis.com/auth/gmail.readonly "
                    + "https://www.googleapis.com/auth/gmail.send";

    @ConfigProperty(name = "casiq.google.client-id") String clientId;
    @ConfigProperty(name = "casiq.google.client-secret") String clientSecret;
    @ConfigProperty(name = "casiq.google.redirect-uri") String redirectUri;
    @ConfigProperty(name = "casiq.google.authorization-state-minutes") long stateMinutes;
    @Inject @RestClient GoogleOAuthClient google;
    @Inject @RestClient GmailProfileClient gmailProfile;
    @Inject WorkAccountService workAccounts;

    private InMemoryAuthorizationStore attempts;

    @PostConstruct
    void initialize() {
        attempts = new InMemoryAuthorizationStore(Duration.ofMinutes(stateMinutes));
    }

    public AuthorizationResponse beginAuthorization() {
        return beginAuthorization(null, null);
    }

    @Override
    public AuthorizationResponse beginAuthorization(Long workAccountId, String loginHint) {
        var pkce = Pkce.create();
        Instant expiresAt = attempts.put(pkce.state(), pkce.codeVerifier(), workAccountId);

        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", clientId);
        parameters.put("redirect_uri", redirectUri);
        parameters.put("response_type", "code");
        parameters.put("scope", SCOPE);
        parameters.put("access_type", "offline");
        parameters.put("prompt", "consent");
        parameters.put("include_granted_scopes", "true");
        parameters.put("state", pkce.state());
        parameters.put("code_challenge", pkce.codeChallenge());
        parameters.put("code_challenge_method", "S256");
        if (loginHint != null && !loginHint.isBlank()) parameters.put("login_hint", loginHint);

        String query = parameters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
        return new AuthorizationResponse(
                "https://accounts.google.com/o/oauth2/v2/auth?" + query, expiresAt);
    }

    @Override
    public String providerCode() {
        return "GOOGLE";
    }

    public ExchangeResult exchange(String state, String code) {
        if (code == null || code.isBlank()) throw new BadRequestException("Missing Google authorization code");
        var attempt = attempts.consume(state)
                .orElseThrow(() -> new BadRequestException("OAuth state is invalid, expired, or already used"));
        var response = google.exchange(clientId, clientSecret, code, attempt.codeVerifier(),
                "authorization_code", redirectUri);
        OAuthTokens tokens = new OAuthTokens(
                response.accessToken(),
                response.refreshToken(),
                Instant.now().plusSeconds(response.expiresIn()),
                response.scope(),
                response.tokenType());
        if (attempt.workAccountId() == null) return new ExchangeResult(tokens, null);

        var profile = gmailProfile.profile("Bearer " + tokens.accessToken());
        if (profile.emailAddress() == null || profile.emailAddress().isBlank()) {
            throw new BadRequestException("Google did not return a Gmail email address");
        }
        WorkAccountView connected = workAccounts.completeEmailConnection(
                attempt.workAccountId(), "GOOGLE", "Google", profile.emailAddress(),
                tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt());
        return new ExchangeResult(null, connected);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record ExchangeResult(OAuthTokens tokens, WorkAccountView workAccount) {}
}
