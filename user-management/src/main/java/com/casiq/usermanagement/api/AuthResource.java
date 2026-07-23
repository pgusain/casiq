package com.casiq.usermanagement.api;

import com.casiq.usermanagement.service.AuthService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;

@Path("/api/v1/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
    public static final String SESSION_COOKIE = "casiq_user_session";

    @Inject AuthService auth;
    @ConfigProperty(name = "casiq.security.cookie-secure") boolean secureCookie;

    @POST
    @Path("/login")
    public Response login(@Valid @NotNull LoginRequest request) {
        AuthService.LoginResult result = auth.login(
                request.companyCode(), request.username(), request.password());
        return Response.ok(result.user())
                .header("Cache-Control", "no-store")
                .header("Set-Cookie", sessionCookie(result.token(), result.expiresAt()))
                .build();
    }

    @GET
    @Path("/me")
    public UserView me(@CookieParam(SESSION_COOKIE) String token) {
        return auth.current(token);
    }

    @POST
    @Path("/password")
    public UserView changePassword(
            @CookieParam(SESSION_COOKIE) String token,
            @Valid @NotNull ChangePasswordRequest request) {
        return auth.changePassword(token, request.currentPassword(), request.newPassword());
    }

    @POST
    @Path("/logout")
    public Response logout(@CookieParam(SESSION_COOKIE) String token) {
        auth.logout(token);
        return Response.noContent().header("Set-Cookie", clearCookie()).build();
    }

    private String sessionCookie(String token, Instant expiresAt) {
        long maxAge = Math.max(0, Duration.between(Instant.now(), expiresAt).toSeconds());
        return SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge
                + (secureCookie ? "; Secure" : "");
    }

    private String clearCookie() {
        return SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"
                + (secureCookie ? "; Secure" : "");
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String companyCode,
            @NotBlank @Size(max = 128) String username,
            @NotBlank @Size(max = 128) String password) {}

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword) {}
}
