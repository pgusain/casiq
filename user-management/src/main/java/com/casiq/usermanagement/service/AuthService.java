package com.casiq.usermanagement.service;

import com.casiq.usermanagement.api.UserView;
import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.usermanagement.persistence.UserSessionEntity;
import com.casiq.usermanagement.security.Hashes;
import com.casiq.usermanagement.security.PasswordService;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@ApplicationScoped
public class AuthService {
    private static final Logger LOG = Logger.getLogger(AuthService.class);
    private static final String DUMMY_BCRYPT = "$2a$12$s5NWxVxAmmSIUsMLM2e9yepYsbtvKaT1mv0xnkatNfhOGuBNCB9Ra";

    @Inject PasswordService passwords;
    @ConfigProperty(name = "casiq.security.session-hours") long sessionHours;

    @Transactional
    public LoginResult login(String companyCode, String username, String password) {
        String tenantCode = companyCode == null || companyCode.isBlank() ? "anonymous" : normalize(companyCode);
        MDC.put("tenantCode", tenantCode);
        try {
            LOG.infof("Attempting login companyCode=%s username=%s", tenantCode, username);
            ApplicationUserEntity user = ApplicationUserEntity.find(
                    "tenant.normalizedCompanyCode = ?1 and normalizedUsername = ?2",
                    normalize(companyCode), normalize(username)).firstResult();
            String expectedHash = user == null ? DUMMY_BCRYPT : user.passwordHash;
            boolean valid = passwords.matches(password, expectedHash);
            if (!valid || user == null || !user.active || !user.tenant.active) {
                LOG.warnf("Login rejected companyCode=%s username=%s valid=%s active=%s tenantActive=%s",
                        tenantCode, username, valid, user != null && user.active, user != null && user.tenant != null && user.tenant.active);
                throw new NotAuthorizedException("Invalid company code, username, or password");
            }

            UserSessionEntity session = new UserSessionEntity();
            String rawToken = Hashes.randomToken();
            session.user = user;
            session.tokenHash = Hashes.sha256(rawToken);
            session.createdAt = Instant.now();
            session.expiresAt = session.createdAt.plus(sessionHours, ChronoUnit.HOURS);
            Panache.getEntityManager().persist(session);
            LOG.infof("Login succeeded companyCode=%s userId=%s sessionExpiresAt=%s",
                    tenantCode, user.id, session.expiresAt);
            return new LoginResult(rawToken, session.expiresAt, UserView.from(user));
        } catch (RuntimeException failure) {
            LOG.errorf("Login failed companyCode=%s username=%s", tenantCode, username, failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public UserView current(String rawToken) {
        MDC.put("tenantCode", rawToken == null || rawToken.isBlank() ? "anonymous" : "session");
        try {
            LOG.debug("Resolving current user for provided session token");
            return UserView.from(requireEntity(rawToken));
        } catch (RuntimeException failure) {
            LOG.warn("Current user lookup failed", failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public ApplicationUserEntity requireEntity(String rawToken) {
        MDC.put("tenantCode", rawToken == null || rawToken.isBlank() ? "anonymous" : "session");
        try {
            if (rawToken == null || rawToken.isBlank()) throw new NotAuthorizedException("Sign in required");
            UserSessionEntity session = UserSessionEntity.find(
                    "tokenHash = ?1 and expiresAt > ?2", Hashes.sha256(rawToken), Instant.now()).firstResult();
            if (session == null) throw new NotAuthorizedException("Session expired");
            ApplicationUserEntity user = ApplicationUserEntity.findById(session.user.id);
            if (user == null || !user.active) throw new NotAuthorizedException("Account is unavailable");
            user.tenant = TenantEntity.findById(user.tenant.id);
            if (!user.tenant.active) throw new NotAuthorizedException("Company is unavailable");
            LOG.debugf("Session validated userId=%s tenantCode=%s", user.id, user.tenant.normalizedCompanyCode);
            return user;
        } catch (RuntimeException failure) {
            LOG.warn("Session validation failed", failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    public void requireCompletedPasswordChange(ApplicationUserEntity user) {
        if (user.mustChangePassword) {
            LOG.warnf("Password change required for userId=%s", user.id);
            throw new WebApplicationException("Password change required before continuing", 409);
        }
    }

    @Transactional
    public UserView changePassword(String rawToken, String currentPassword, String newPassword) {
        MDC.put("tenantCode", rawToken == null || rawToken.isBlank() ? "anonymous" : "session");
        try {
            LOG.info("Password change requested");
            ApplicationUserEntity user = requireEntity(rawToken);
            if (!passwords.matches(currentPassword, user.passwordHash)) {
                throw new NotAuthorizedException("Current password is incorrect");
            }
            if (passwords.matches(newPassword, user.passwordHash)) {
                throw new WebApplicationException("New password must be different from the current password", 400);
            }
            user.passwordHash = passwords.hash(newPassword);
            user.mustChangePassword = false;
            user.passwordChangedAt = Instant.now();
            user.updatedAt = user.passwordChangedAt;
            UserSessionEntity.delete("user.id = ?1 and tokenHash <> ?2", user.id, Hashes.sha256(rawToken));
            LOG.infof("Password change completed userId=%s", user.id);
            return UserView.from(user);
        } catch (RuntimeException failure) {
            LOG.error("Password change failed", failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public void logout(String rawToken) {
        MDC.put("tenantCode", rawToken == null || rawToken.isBlank() ? "anonymous" : "session");
        try {
            LOG.info("Logout requested");
            if (rawToken != null && !rawToken.isBlank()) {
                UserSessionEntity.delete("tokenHash", Hashes.sha256(rawToken));
            }
            LOG.info("Logout completed");
        } catch (RuntimeException failure) {
            LOG.warn("Logout failed", failure);
            throw failure;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public record LoginResult(String token, Instant expiresAt, UserView user) {}
}
