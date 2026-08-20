package com.casiq.usermanagement.service;

import com.casiq.usermanagement.api.TenantView;
import com.casiq.usermanagement.domain.UserRole;
import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import com.casiq.usermanagement.persistence.TenantEntity;
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
public class TenantAdministrationService {
    private static final Logger LOG = Logger.getLogger(TenantAdministrationService.class);
    @Inject AuthService auth;

    @Transactional
    public List<TenantView> list(String rawToken) {
        ApplicationUserEntity actor = requireGlobalAdmin(rawToken);
        MDC.put("tenantCode", actor.tenant != null ? actor.tenant.normalizedCompanyCode : "global");
        try {
            LOG.debugf("Listing tenants for actorId=%s", actor.id);
            return TenantEntity.<TenantEntity>list("order by companyCode")
                    .stream().map(TenantView::from).toList();
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error listing tenants for actorId=%s", actor.id, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public TenantView create(String rawToken, String companyCode, String displayName, boolean active) {
        ApplicationUserEntity actor = requireGlobalAdmin(rawToken);
        MDC.put("tenantCode", companyCode == null ? "unknown" : normalize(companyCode));
        try {
            LOG.debugf("Creating tenant actorId=%s companyCode=%s active=%s", String.valueOf(actor.id), companyCode, active);
            String normalizedCode = normalize(companyCode);
            ensureUniqueCompanyCode(normalizedCode, null);

            Instant now = Instant.now();
            TenantEntity tenant = new TenantEntity();
            tenant.companyCode = companyCode.trim();
            tenant.normalizedCompanyCode = normalizedCode;
            tenant.displayName = displayName.trim();
            tenant.active = active;
            tenant.createdAt = now;
            tenant.updatedAt = now;
            Panache.getEntityManager().persist(tenant);
            LOG.infof("Created tenant tenantId=%s companyCode=%s actorId=%s", tenant.id, tenant.companyCode, actor.id);
            return TenantView.from(tenant);
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error creating tenant actorId=%s companyCode=%s", actor.id, companyCode, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public TenantView update(String rawToken, Long tenantId, String companyCode,
                             String displayName, boolean active) {
        ApplicationUserEntity actor = requireGlobalAdmin(rawToken);
        MDC.put("tenantCode", companyCode == null ? "unknown" : normalize(companyCode));
        try {
            LOG.debugf("Updating tenant actorId=%s tenantId=%s active=%s", String.valueOf(actor.id), String.valueOf(tenantId), active);
            TenantEntity tenant = TenantEntity.findById(tenantId);
            if (tenant == null) throw new NotFoundException("Tenant not found");
            if (actor.tenant.id.equals(tenantId) && !active) {
                throw new WebApplicationException("You cannot deactivate your current tenant", 400);
            }

            String normalizedCode = normalize(companyCode);
            ensureUniqueCompanyCode(normalizedCode, tenantId);
            tenant.companyCode = companyCode.trim();
            tenant.normalizedCompanyCode = normalizedCode;
            tenant.displayName = displayName.trim();
            tenant.active = active;
            tenant.updatedAt = Instant.now();
            LOG.infof("Updated tenant tenantId=%s companyCode=%s actorId=%s", tenant.id, tenant.companyCode, actor.id);
            return TenantView.from(tenant);
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error updating tenant actorId=%s tenantId=%s", actor.id, tenantId, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    private ApplicationUserEntity requireGlobalAdmin(String rawToken) {
        ApplicationUserEntity actor = auth.requireEntity(rawToken);
        auth.requireCompletedPasswordChange(actor);
        if (actor.role != UserRole.GLOBAL_ADMIN) {
            throw new ForbiddenException("GLOBAL_ADMIN role required");
        }
        return actor;
    }

    private void ensureUniqueCompanyCode(String normalizedCode, Long excludedId) {
        long matches = excludedId == null
                ? TenantEntity.count("normalizedCompanyCode", normalizedCode)
                : TenantEntity.count("normalizedCompanyCode = ?1 and id <> ?2", normalizedCode, excludedId);
        if (matches > 0) throw new WebApplicationException("Company code already exists", 409);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
