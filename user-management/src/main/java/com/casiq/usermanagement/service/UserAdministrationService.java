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
import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class UserAdministrationService {
    private static final Logger LOG = Logger.getLogger(UserAdministrationService.class);
    @Inject AuthService auth;
    @Inject PasswordService passwords;

    @Transactional
    public List<UserView> list(String rawToken) {
        ApplicationUserEntity actor = administrator(rawToken);
        MDC.put("tenantCode", actor.tenant != null ? actor.tenant.normalizedCompanyCode : "unknown");
        try {
            LOG.debugf("Listing users for actorId=%s tenantCode=%s", String.valueOf(actor.id), actor.tenant != null ? actor.tenant.normalizedCompanyCode : "unknown");
            List<ApplicationUserEntity> users = actor.role == UserRole.GLOBAL_ADMIN
                    ? ApplicationUserEntity.list("order by tenant.companyCode, username")
                    : ApplicationUserEntity.list("tenant.id = ?1 order by username", actor.tenant.id);
            users.forEach(user -> user.tenant = TenantEntity.findById(user.tenant.id));
            return users.stream().map(UserView::from).toList();
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error listing users for actorId=%s tenantCode=%s", String.valueOf(actor.id), actor.tenant != null ? actor.tenant.normalizedCompanyCode : "unknown");
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public UserView create(String rawToken, String companyCode, String username,
                           String firstName, String lastName,
                           String temporaryPassword, UserRole role) {
        ApplicationUserEntity actor = administrator(rawToken);
        MDC.put("tenantCode", companyCode == null ? "unknown" : normalize(companyCode));
        try {
            LOG.debugf("Creating user actorId=%s companyCode=%s username=%s role=%s", String.valueOf(actor.id), companyCode, username, role);
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
            user.firstName = firstName.trim();
            user.lastName = lastName.trim();
            user.passwordHash = passwords.hash(temporaryPassword);
            user.role = role;
            user.mustChangePassword = true;
            user.active = true;
            user.createdAt = now;
            user.updatedAt = now;
            Panache.getEntityManager().persist(user);
            LOG.infof("Created user userId=%s tenantId=%s username=%s role=%s actorId=%s",
                    user.id, tenant.id, user.username, role, actor.id);
            return UserView.from(user);
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error creating user for actorId=%s companyCode=%s username=%s", String.valueOf(actor.id), companyCode, username, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public UserView update(String rawToken, Long targetId, String username,
                           String firstName, String lastName,
                           UserRole role, boolean active) {
        ApplicationUserEntity actor = administrator(rawToken);
        MDC.put("tenantCode", actor.tenant != null ? actor.tenant.normalizedCompanyCode : "unknown");
        try {
            LOG.debugf("Updating user actorId=%s targetId=%s role=%s active=%s", String.valueOf(actor.id), String.valueOf(targetId), role, active);
            ApplicationUserEntity target = ApplicationUserEntity.findById(targetId);
            if (target == null) throw new NotFoundException("User not found");
            target.tenant = TenantEntity.findById(target.tenant.id);
            assertTargetCanBeManaged(actor, target);
            if (!actor.id.equals(target.id)) {
                assertRoleCanBeManaged(actor, role);
            }

            if (actor.id.equals(target.id)
                    && (role != target.role || !active)) {
                throw new WebApplicationException(
                        "You cannot change your own role or deactivate your own account", 400);
            }
            if (target.role == UserRole.GLOBAL_ADMIN
                    && target.active
                    && (role != UserRole.GLOBAL_ADMIN || !active)
                    && ApplicationUserEntity.count(
                           "role = ?1 and active = true and id <> ?2",
                           UserRole.GLOBAL_ADMIN, target.id) == 0) {
                throw new WebApplicationException(
                        "At least one active GLOBAL_ADMIN account is required", 409);
            }

            String normalizedUsername = normalize(username);
            if (ApplicationUserEntity.count(
                    "tenant.id = ?1 and normalizedUsername = ?2 and id <> ?3",
                    target.tenant.id, normalizedUsername, target.id) > 0) {
                throw new WebApplicationException(
                        "Username already exists for this company", 409);
            }

            boolean loginIdentityChanged =
                    !target.normalizedUsername.equals(normalizedUsername)
                           || target.role != role
                           || target.active != active;
            target.username = username.trim();
            target.normalizedUsername = normalizedUsername;
            target.firstName = firstName.trim();
            target.lastName = lastName.trim();
            target.role = role;
            target.active = active;
            target.updatedAt = Instant.now();
            if (loginIdentityChanged && !actor.id.equals(target.id)) {
                UserSessionEntity.delete("user.id", target.id);
            }
            LOG.infof(
                    "Updated user userId=%s tenantId=%s username=%s role=%s active=%s actorId=%s",
                    target.id, target.tenant.id, target.username, role, active, actor.id);
            return UserView.from(target);
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error updating user actorId=%s targetId=%s", actor.id, targetId, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public UserView resetPassword(String rawToken, Long targetId, String temporaryPassword) {
        ApplicationUserEntity actor = administrator(rawToken);
        MDC.put("tenantCode", actor.tenant != null ? actor.tenant.normalizedCompanyCode : "unknown");
        try {
            LOG.debugf("Resetting password for actorId=%s targetId=%s", String.valueOf(actor.id), String.valueOf(targetId));
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
            LOG.infof("Reset password for userId=%s tenantId=%s actorId=%s", target.id, target.tenant.id, actor.id);
            return UserView.from(target);
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error resetting password for actorId=%s targetId=%s", String.valueOf(actor.id), String.valueOf(targetId), e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
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
        if (actor.id.equals(target.id) || actor.role == UserRole.GLOBAL_ADMIN) return;
        if (!actor.tenant.id.equals(target.tenant.id)
                || target.role == UserRole.ADMIN || target.role == UserRole.GLOBAL_ADMIN) {
            throw new ForbiddenException("User is outside your administration scope");
        }
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
