package com.casiq.workaccount.microsoft;

import com.casiq.workaccount.core.api.WorkAccountView;
import com.casiq.workaccount.core.service.EmailProviderAuthorization;
import com.casiq.workaccount.core.service.WorkAccountService;
import com.casiq.workaccount.oauth.InMemoryAuthorizationStore;
import com.casiq.workaccount.oauth.OAuthTokens;
import com.casiq.workaccount.oauth.Pkce;
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
public class MicrosoftOAuthService implements EmailProviderAuthorization {
    static final String SCOPE =
            "openid profile email offline_access User.Read Mail.ReadWrite Mail.Send";

    @ConfigProperty(name = "casiq.microsoft.client-id") String clientId;
    @ConfigProperty(name = "casiq.microsoft.client-secret") String clientSecret;
    @ConfigProperty(name = "casiq.microsoft.tenant") String tenant;
    @ConfigProperty(name = "casiq.microsoft.redirect-uri") String redirectUri;
    @ConfigProperty(name = "casiq.microsoft.authorization-state-minutes") long stateMinutes;
    @Inject @RestClient MicrosoftOAuthClient microsoft;
    @Inject @RestClient MicrosoftGraphClient graph;
    @Inject WorkAccountService workAccounts;

    private InMemoryAuthorizationStore attempts;

    @PostConstruct
    void initialize() {
        attempts = new InMemoryAuthorizationStore(Duration.ofMinutes(stateMinutes));
    }

    @Override
    public String providerCode() {
        return "MICROSOFT";
    }

    @Override
    public AuthorizationResponse beginAuthorization(Long workAccountId, String loginHint) {
        var pkce = Pkce.create();
        Instant expiresAt = attempts.put(pkce.state(), pkce.codeVerifier(), workAccountId);
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", clientId);
        parameters.put("response_type", "code");
        parameters.put("redirect_uri", redirectUri);
        parameters.put("response_mode", "query");
        parameters.put("scope", SCOPE);
        parameters.put("state", pkce.state());
        parameters.put("code_challenge", pkce.codeChallenge());
        parameters.put("code_challenge_method", "S256");
        parameters.put("prompt", "select_account");
        if (loginHint != null && !loginHint.isBlank()) parameters.put("login_hint", loginHint);
        String query = parameters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
        return new AuthorizationResponse(
                "https://login.microsoftonline.com/" + encodePath(tenant)
                        + "/oauth2/v2.0/authorize?" + query,
                expiresAt);
    }

    public ExchangeResult exchange(String state, String code) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("Missing Microsoft authorization code");
        }
        var attempt = attempts.consume(state)
                .orElseThrow(() -> new BadRequestException(
                        "OAuth state is invalid, expired, or already used"));
        var response = microsoft.exchange(
                tenant, clientId, clientSecret, code, attempt.codeVerifier(),
                "authorization_code", redirectUri, SCOPE);
        OAuthTokens tokens = new OAuthTokens(
                response.accessToken(),
                response.refreshToken(),
                Instant.now().plusSeconds(response.expiresIn()),
                response.scope(),
                response.tokenType());
        MicrosoftGraphClient.GraphUser user =
                graph.me("Bearer " + tokens.accessToken(), "mail,userPrincipalName");
        String email = user == null || user.mail() == null || user.mail().isBlank()
                ? user == null ? null : user.userPrincipalName()
                : user.mail();
        if (email == null || email.isBlank()) {
            throw new BadRequestException("Microsoft did not return a mailbox email address");
        }
        WorkAccountView connected = workAccounts.completeEmailConnection(
                attempt.workAccountId(), "MICROSOFT", "Microsoft 365", email,
                tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt());
        return new ExchangeResult(connected);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    public record ExchangeResult(WorkAccountView workAccount) {
    }
}
