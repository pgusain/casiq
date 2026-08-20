package com.casiq.usermanagement.api;

import com.casiq.usermanagement.service.TenantAdministrationService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

@Path("/api/v1/tenants")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TenantAdministrationResource {
    private static final Logger LOG = Logger.getLogger(TenantAdministrationResource.class);
    @Inject TenantAdministrationService tenants;

    @GET
    public List<TenantView> list(@CookieParam(AuthResource.SESSION_COOKIE) String token) {
        return tenants.list(token);
    }

    @POST
    public TenantView create(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @Valid @NotNull TenantRequest request) {
        MDC.put("tenantCode", request.companyCode());
        try {
            LOG.debugf("Entering create companyCode=%s", request.companyCode());
            return tenants.create(token, request.companyCode(), request.displayName(), request.active());
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @PUT
    @Path("/{id}")
    public TenantView update(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("id") Long id,
            @Valid @NotNull TenantRequest request) {
        MDC.put("tenantCode", request.companyCode());
        try {
            LOG.debugf("Entering update id=%s companyCode=%s", id, request.companyCode());
            return tenants.update(token, id, request.companyCode(), request.displayName(), request.active());
        } finally {
            MDC.remove("tenantCode");
        }
    }

    public record TenantRequest(
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*",
                    message = "companyCode may contain only letters, numbers, hyphens, and underscores")
            String companyCode,
            @NotBlank @Size(max = 160) String displayName,
            @NotNull Boolean active) {}
}
