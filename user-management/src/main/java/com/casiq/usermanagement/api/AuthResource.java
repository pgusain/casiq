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
import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;

@Path("/api/v1/auth")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
    private static final Logger LOG = Logger.getLogger(AuthResource.class);
    public static final String SESSION_COOKIE = "casiq_user_session";

    @Inject AuthService auth;
    @ConfigProperty(name = "casiq.security.cookie-secure") boolean secureCookie;

    @POST
    @Path("/login")
    public Response login(@Valid @NotNull LoginRequest request) {
        MDC.put("tenantCode", request.companyCode() == null ? "anonymous" : request.companyCode());
        try {
            LOG.infof("Login request received companyCode=%s username=%s", request.companyCode(), request.username());
            AuthService.LoginResult result = auth.login(
                    request.companyCode(), request.username(), request.password());
            LOG.infof("Login succeeded companyCode=%s username=%s", request.companyCode(), request.username());
            return Response.ok(result.user())
                    .header("Cache-Control", "no-store")
                    .header("Set-Cookie", sessionCookie(result.token(), result.expiresAt()))
                    .build();
        } catch (RuntimeException failure) {
            LOG.errorf("Login failed companyCode=%s username=%s", request.companyCode(), request.username(), failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @GET
    @Path("/me")
    public UserView me(@CookieParam(SESSION_COOKIE) String token) {
        MDC.put("tenantCode", token == null || token.isBlank() ? "anonymous" : "session");
        try {
            LOG.debug("Fetching current authenticated user");
            return auth.current(token);
        } catch (RuntimeException failure) {
            LOG.warn("Unable to resolve current authenticated user", failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @POST
    @Path("/password")
    public UserView changePassword(
            @CookieParam(SESSION_COOKIE) String token,
            @Valid @NotNull ChangePasswordRequest request) {
        MDC.put("tenantCode", token == null || token.isBlank() ? "anonymous" : "session");
        try {
            LOG.info("Password change request received");
            UserView user = auth.changePassword(token, request.currentPassword(), request.newPassword());
            LOG.info("Password change completed");
            return user;
        } catch (RuntimeException failure) {
            LOG.error("Password change failed", failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @POST
    @Path("/logout")
    public Response logout(@CookieParam(SESSION_COOKIE) String token) {
        MDC.put("tenantCode", token == null || token.isBlank() ? "anonymous" : "session");
        try {
            LOG.info("Logout request received");
            auth.logout(token);
            LOG.info("Logout completed");
            return Response.noContent().header("Set-Cookie", clearCookie()).build();
        } catch (RuntimeException failure) {
            LOG.warn("Logout failed", failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
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
