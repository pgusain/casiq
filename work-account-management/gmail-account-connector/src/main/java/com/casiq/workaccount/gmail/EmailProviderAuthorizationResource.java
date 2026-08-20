package com.casiq.workaccount.gmail;

import com.casiq.usermanagement.api.AuthResource;
import com.casiq.workaccount.core.service.WorkAccountService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

@Path("/api/v1/work-accounts")
@Produces(MediaType.APPLICATION_JSON)
public class EmailProviderAuthorizationResource {
    private static final Logger LOG = Logger.getLogger(EmailProviderAuthorizationResource.class);

    @Inject WorkAccountService workAccounts;
    @Inject GmailOAuthService gmail;

    @POST
    @Path("/{id}/authorize")
    public GmailOAuthService.AuthorizationResponse authorize(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("id") Long id) {
        MDC.put("tenantCode", token == null || token.isBlank() ? "anonymous" : "session");
        try {
            LOG.infof("Starting email provider authorization for workAccountId=%s", id);
            WorkAccountService.WorkAccountTarget account = workAccounts.requireManageable(token, id);
            return switch (account.provider()) {
                case "GOOGLE" -> {
                    LOG.infof("Redirecting Google authorization for workAccountId=%s", account.id());
                    yield gmail.beginAuthorization(account.id(), account.emailId());
                }
                case "MICROSOFT" -> throw new WebApplicationException(
                        "Microsoft email connector is not configured yet", 501);
                default -> throw new BadRequestException("Unsupported email provider");
            };
        } catch (RuntimeException failure) {
            LOG.errorf("Email provider authorization failed workAccountId=%s", id, failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
    }
}
