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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@ApplicationScoped
public class AuthService {
    private static final String DUMMY_BCRYPT = "$2a$12$s5NWxVxAmmSIUsMLM2e9yepYsbtvKaT1mv0xnkatNfhOGuBNCB9Ra";

    @Inject PasswordService passwords;
    @ConfigProperty(name = "casiq.security.session-hours") long sessionHours;

    @Transactional
    public LoginResult login(String companyCode, String username, String password) {
        ApplicationUserEntity user = ApplicationUserEntity.find(
                "tenant.normalizedCompanyCode = ?1 and normalizedUsername = ?2",
                normalize(companyCode), normalize(username)).firstResult();
        String expectedHash = user == null ? DUMMY_BCRYPT : user.passwordHash;
        boolean valid = passwords.matches(password, expectedHash);
        if (!valid || user == null || !user.active || !user.tenant.active) {
            throw new NotAuthorizedException("Invalid company code, username, or password");
        }

        UserSessionEntity session = new UserSessionEntity();
        String rawToken = Hashes.randomToken();
        session.user = user;
        session.tokenHash = Hashes.sha256(rawToken);
        session.createdAt = Instant.now();
        session.expiresAt = session.createdAt.plus(sessionHours, ChronoUnit.HOURS);
        Panache.getEntityManager().persist(session);
        return new LoginResult(rawToken, session.expiresAt, UserView.from(user));
    }

    @Transactional
    public UserView current(String rawToken) {
        return UserView.from(requireEntity(rawToken));
    }

    @Transactional
    public ApplicationUserEntity requireEntity(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new NotAuthorizedException("Sign in required");
        UserSessionEntity session = UserSessionEntity.find(
                "tokenHash = ?1 and expiresAt > ?2", Hashes.sha256(rawToken), Instant.now()).firstResult();
        if (session == null) throw new NotAuthorizedException("Session expired");
        ApplicationUserEntity user = ApplicationUserEntity.findById(session.user.id);
        if (user == null || !user.active) throw new NotAuthorizedException("Account is unavailable");
        user.tenant = TenantEntity.findById(user.tenant.id);
        if (!user.tenant.active) throw new NotAuthorizedException("Company is unavailable");
        return user;
    }

    public void requireCompletedPasswordChange(ApplicationUserEntity user) {
        if (user.mustChangePassword) {
            throw new WebApplicationException("Password change required before continuing", 409);
        }
    }

    @Transactional
    public UserView changePassword(String rawToken, String currentPassword, String newPassword) {
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
        return UserView.from(user);
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) {
            UserSessionEntity.delete("tokenHash", Hashes.sha256(rawToken));
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public record LoginResult(String token, Instant expiresAt, UserView user) {}
}
