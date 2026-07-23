package com.casiq.usermanagement.service;

import com.casiq.usermanagement.api.UserView;
import com.casiq.usermanagement.domain.UserRole;
import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.usermanagement.persistence.UserSessionEntity;
import com.casiq.usermanagement.security.PasswordService;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class UserAdministrationService {
    @Inject AuthService auth;
    @Inject PasswordService passwords;

    @Transactional
    public List<UserView> list(String rawToken) {
        ApplicationUserEntity actor = administrator(rawToken);
        List<ApplicationUserEntity> users = actor.role == UserRole.GLOBAL_ADMIN
                ? ApplicationUserEntity.list("order by tenant.companyCode, username")
                : ApplicationUserEntity.list("tenant.id = ?1 order by username", actor.tenant.id);
        users.forEach(user -> user.tenant = TenantEntity.findById(user.tenant.id));
        return users.stream().map(UserView::from).toList();
    }

    @Transactional
    public UserView create(String rawToken, String companyCode, String username,
                           String temporaryPassword, UserRole role) {
        ApplicationUserEntity actor = administrator(rawToken);
        assertRoleCanBeManaged(actor, role);
        TenantEntity tenant = resolveTargetTenant(actor, companyCode);
        String normalizedUsername = normalize(username);
        if (ApplicationUserEntity.count("tenant.id = ?1 and normalizedUsername = ?2", tenant.id, normalizedUsername) > 0) {
            throw new WebApplicationException("Username already exists for this company", 409);
        }

        Instant now = Instant.now();
        ApplicationUserEntity user = new ApplicationUserEntity();
        user.tenant = tenant;
        user.username = username.trim();
        user.normalizedUsername = normalizedUsername;
        user.passwordHash = passwords.hash(temporaryPassword);
        user.role = role;
        user.mustChangePassword = true;
        user.active = true;
        user.createdAt = now;
        user.updatedAt = now;
        Panache.getEntityManager().persist(user);
        return UserView.from(user);
    }

    @Transactional
    public UserView resetPassword(String rawToken, UUID targetId, String temporaryPassword) {
        ApplicationUserEntity actor = administrator(rawToken);
        if (actor.id.equals(targetId)) {
            throw new WebApplicationException("Use change password for your own account", 400);
        }
        ApplicationUserEntity target = ApplicationUserEntity.findById(targetId);
        if (target == null) throw new NotFoundException("User not found");
        target.tenant = TenantEntity.findById(target.tenant.id);
        assertTargetCanBeManaged(actor, target);

        target.passwordHash = passwords.hash(temporaryPassword);
        target.mustChangePassword = true;
        target.passwordChangedAt = null;
        target.updatedAt = Instant.now();
        UserSessionEntity.delete("user.id", target.id);
        return UserView.from(target);
    }

    private ApplicationUserEntity administrator(String rawToken) {
        ApplicationUserEntity actor = auth.requireEntity(rawToken);
        auth.requireCompletedPasswordChange(actor);
        if (!actor.role.canAdministerUsers()) throw new ForbiddenException("Administrator role required");
        return actor;
    }

    private TenantEntity resolveTargetTenant(ApplicationUserEntity actor, String requestedCompanyCode) {
        if (actor.role == UserRole.ADMIN) return actor.tenant;
        String code = requestedCompanyCode == null || requestedCompanyCode.isBlank()
                ? actor.tenant.normalizedCompanyCode : normalize(requestedCompanyCode);
        TenantEntity tenant = TenantEntity.find("normalizedCompanyCode = ?1 and active = true", code).firstResult();
        if (tenant == null) throw new NotFoundException("Company not found");
        return tenant;
    }

    private void assertRoleCanBeManaged(ApplicationUserEntity actor, UserRole targetRole) {
        if (actor.role == UserRole.ADMIN && (targetRole == UserRole.ADMIN || targetRole == UserRole.GLOBAL_ADMIN)) {
            throw new ForbiddenException("ADMIN can create only PROCESSOR or BASE_USER accounts");
        }
    }

    private void assertTargetCanBeManaged(ApplicationUserEntity actor, ApplicationUserEntity target) {
        if (actor.role == UserRole.GLOBAL_ADMIN) return;
        if (!actor.tenant.id.equals(target.tenant.id)
                || target.role == UserRole.ADMIN || target.role == UserRole.GLOBAL_ADMIN) {
            throw new ForbiddenException("User is outside your administration scope");
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
