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
import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class MicrosoftOAuthService implements EmailProviderAuthorization {
    private static final Logger LOG = Logger.getLogger(MicrosoftOAuthService.class);
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
        LOG.infof("Initialized Microsoft OAuth authorization tracker with state validity minutes=%d", stateMinutes);
    }

    @Override
    public String providerCode() {
        return "MICROSOFT";
    }

    @Override
    public AuthorizationResponse beginAuthorization(Long workAccountId, String loginHint) {
        MDC.put("tenantCode", workAccountId == null ? "oauth" : String.valueOf(workAccountId));
        try {
            LOG.infof("Starting Microsoft authorization request workAccountId=%s loginHintProvided=%s", workAccountId,
                    loginHint != null && !loginHint.isBlank());
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
            LOG.infof("Generated Microsoft authorization URL workAccountId=%s expiresAt=%s", workAccountId, expiresAt);
            return new AuthorizationResponse(
                    "https://login.microsoftonline.com/" + encodePath(tenant)
                            + "/oauth2/v2.0/authorize?" + query,
                    expiresAt);
        } catch (RuntimeException failure) {
            LOG.errorf("Failed to generate Microsoft authorization URL workAccountId=%s", workAccountId, failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    public ExchangeResult exchange(String state, String code) {
        MDC.put("tenantCode", state == null || state.isBlank() ? "oauth" : "oauth-state");
        try {
            if (code == null || code.isBlank()) {
                throw new BadRequestException("Missing Microsoft authorization code");
            }
            LOG.infof("Exchanging Microsoft authorization code state=%s", state);
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
            LOG.infof("Microsoft OAuth exchange linked workAccountId=%s email=%s", attempt.workAccountId(), email);
            return new ExchangeResult(connected);
        } catch (RuntimeException failure) {
            LOG.errorf("Microsoft OAuth exchange failed state=%s", state, failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
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
