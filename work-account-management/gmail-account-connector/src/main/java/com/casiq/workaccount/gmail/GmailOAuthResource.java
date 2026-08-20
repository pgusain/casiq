package com.casiq.workaccount.gmail;

import com.casiq.usermanagement.api.AuthResource;
import com.casiq.workaccount.core.service.WorkAccountService;
import com.casiq.workaccount.core.service.EmailProviderAuthorization;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Context;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.util.Map;

@Path("/api/v1/gmail")
@Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_HTML})
public class GmailOAuthResource {
    private static final Logger LOG = Logger.getLogger(GmailOAuthResource.class);

    @Inject GmailOAuthService gmail;
    @Inject OAuthCallbackPage callbackPage;
    @Inject WorkAccountService workAccounts;

    @POST
    @Path("/authorize")
    public EmailProviderAuthorization.AuthorizationResponse authorize() {
        MDC.put("tenantCode", "oauth");
        try {
            LOG.info("Starting Google authorization flow");
            return gmail.beginAuthorization();
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @POST
    @Path("/work-accounts/{id}/authorize")
    public EmailProviderAuthorization.AuthorizationResponse authorizeWorkAccount(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("id") Long id) {
        MDC.put("tenantCode", token == null || token.isBlank() ? "anonymous" : "session");
        try {
            LOG.infof("Starting work-account Google authorization flow workAccountId=%s", id);
            var account = workAccounts.requireManageable(token, id);
            if (!"GOOGLE".equals(account.provider())) {
                throw new BadRequestException("The selected work account is not a Google account");
            }
            return gmail.beginAuthorization(account.id(), account.emailId());
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @GET
    @Path("/callback")
    public Response callback(
            @QueryParam("state") String state,
            @QueryParam("code") String code,
            @QueryParam("error") String error,
            @Context HttpHeaders headers) {
        String accept = headers.getHeaderString(HttpHeaders.ACCEPT);
        boolean browser = accept != null && accept.contains(MediaType.TEXT_HTML);
        MDC.put("tenantCode", "oauth");
        try {
            LOG.infof("Processing Google OAuth callback state=%s error=%s", state, error);
            if (error != null) throw new BadRequestException("Google authorization failed: " + error);
            GmailOAuthService.ExchangeResult result = gmail.exchange(state, code);
            Object payload = result.workAccount() == null ? result.tokens() : result.workAccount();
            Map<String, Object> browserPayload = result.workAccount() == null
                    ? Map.of("tokens", result.tokens()) : Map.of("workAccount", result.workAccount());
            LOG.infof("Google OAuth callback completed state=%s workAccountLinked=%s", state, result.workAccount() != null);
            return browser
                    ? Response.ok(callbackPage.success(browserPayload), MediaType.TEXT_HTML).build()
                    : Response.ok(payload, MediaType.APPLICATION_JSON).build();
        } catch (BadRequestException exception) {
            LOG.warnf("Google OAuth callback rejected state=%s", state, exception);
            if (!browser) throw exception;
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_HTML).entity(callbackPage.error(exception.getMessage())).build();
        } catch (RuntimeException exception) {
            LOG.errorf("Google OAuth callback failed state=%s", state, exception);
            if (!browser) throw exception;
            return Response.status(Response.Status.BAD_GATEWAY).type(MediaType.TEXT_HTML)
                    .entity(callbackPage.error("Google token exchange failed. Check the OAuth credentials and redirect URI."))
                    .build();
        } finally {
            MDC.remove("tenantCode");
        }
    }
}
