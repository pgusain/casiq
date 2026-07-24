package com.casiq.usermanagement.api;

import com.casiq.usermanagement.domain.UserRole;
import com.casiq.usermanagement.service.UserAdministrationService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
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
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.UUID;

@Path("/api/v1/users")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UserAdministrationResource {
    @Inject UserAdministrationService users;

    @GET
    public List<UserView> list(@CookieParam(AuthResource.SESSION_COOKIE) String token) {
        return users.list(token);
    }

    @POST
    public UserView create(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @Valid @NotNull CreateUserRequest request) {
        return users.create(token, request.companyCode(), request.username(),
                request.firstName(), request.lastName(),
                request.temporaryPassword(), request.role());
    }

    @PUT
    @Path("/{id}")
    public UserView update(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("id") UUID id,
            @Valid @NotNull UpdateUserRequest request) {
        return users.update(token, id, request.username(), request.firstName(),
                request.lastName(), request.role(), request.active());
    }

    @POST
    @Path("/{id}/reset-password")
    public UserView resetPassword(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("id") UUID id,
            @Valid @NotNull ResetPasswordRequest request) {
        return users.resetPassword(token, id, request.temporaryPassword());
    }

    public record CreateUserRequest(
            @Size(max = 64) String companyCode,
            @NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 128) String firstName,
            @NotBlank @Size(max = 128) String lastName,
            @NotBlank @Size(min = 12, max = 128) String temporaryPassword,
            @NotNull UserRole role) {}

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 12, max = 128) String temporaryPassword) {}

    public record UpdateUserRequest(
            @NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 128) String firstName,
            @NotBlank @Size(max = 128) String lastName,
            @NotNull UserRole role,
            boolean active) {}
}
