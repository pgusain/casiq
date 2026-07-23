package com.casiq.workaccount.gmail;

import com.casiq.usermanagement.api.AuthResource;
import com.casiq.workaccount.core.service.WorkAccountService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.UUID;

@Path("/api/v1/work-accounts")
@Produces(MediaType.APPLICATION_JSON)
public class EmailProviderAuthorizationResource {
    @Inject WorkAccountService workAccounts;
    @Inject GmailOAuthService gmail;

    @POST
    @Path("/{id}/authorize")
    public GmailOAuthService.AuthorizationResponse authorize(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("id") UUID id) {
        WorkAccountService.WorkAccountTarget account = workAccounts.requireManageable(token, id);
        return switch (account.provider()) {
            case "GOOGLE" -> gmail.beginAuthorization(account.id(), account.emailId());
            case "MICROSOFT" -> throw new WebApplicationException(
                    "Microsoft email connector is not configured yet", 501);
            default -> throw new BadRequestException("Unsupported email provider");
        };
    }
}
