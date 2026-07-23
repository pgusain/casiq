package com.casiq.workaccount.core.api;

import com.casiq.usermanagement.api.AuthResource;
import com.casiq.workaccount.core.service.WorkAccountService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/v1/work-accounts")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WorkAccountResource {
    @Inject WorkAccountService workAccounts;

    @GET
    public List<WorkAccountView> list(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @QueryParam("tenantId") UUID tenantId) {
        return workAccounts.list(token, tenantId);
    }

    @GET
    @Path("/providers")
    public List<WorkAccountService.EmailProviderView> providers(
            @CookieParam(AuthResource.SESSION_COOKIE) String token) {
        return workAccounts.providers(token);
    }

    @POST
    public WorkAccountView create(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @Valid @NotNull WorkAccountRequest request) {
        return workAccounts.create(token, request.tenantId(), request.emailId(), request.provider(), request.workItemId());
    }

    @PUT
    @Path("/{id}")
    public WorkAccountView update(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("id") UUID id,
            @Valid @NotNull WorkAccountRequest request) {
        return workAccounts.update(token, id, request.emailId(), request.provider(), request.workItemId());
    }

    public record WorkAccountRequest(
            UUID tenantId,
            @NotBlank @Email @Size(max = 320) String emailId,
            @NotBlank @Size(max = 32) String provider,
            @NotNull UUID workItemId) {}
}
