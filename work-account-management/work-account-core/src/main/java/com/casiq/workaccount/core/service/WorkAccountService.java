package com.casiq.workaccount.core.service;

import com.casiq.usermanagement.domain.UserRole;
import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.usermanagement.service.AuthService;
import com.casiq.workaccount.core.api.WorkAccountView;
import com.casiq.workaccount.core.persistence.WorkAccountEntity;
import com.casiq.workaccount.core.persistence.EmailPollingConfigEntity;
import com.casiq.workaccount.core.persistence.EmailProviderEntity;
import com.casiq.workitem.persistence.WorkItemDefinitionEntity;
import com.casiq.workitem.service.WorkItemDefinitionService;
import com.casiq.workitem.service.WorkItemWorkflowService;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class WorkAccountService {
    @Inject AuthService auth;
    @Inject WorkItemDefinitionService workItems;
    @Inject WorkItemWorkflowService workflows;

    @Transactional
    public List<WorkAccountView> list(String rawToken, UUID requestedTenantId) {
        ApplicationUserEntity actor = administrator(rawToken);
        List<WorkAccountEntity> accounts;
        if (actor.role == UserRole.GLOBAL_ADMIN) {
            accounts = requestedTenantId == null
                    ? WorkAccountEntity.list("order by tenant.companyCode, emailId")
                    : WorkAccountEntity.list("tenant.id = ?1 order by emailId", requestedTenantId);
        } else {
            assertOwnTenant(actor, requestedTenantId);
            accounts = WorkAccountEntity.list("tenant.id = ?1 order by emailId", actor.tenant.id);
        }
        accounts.forEach(this::initializeReferences);
        return accounts.stream().map(this::view).toList();
    }

    @Transactional
    public List<EmailProviderView> providers(String rawToken) {
        administrator(rawToken);
        return EmailProviderEntity.<EmailProviderEntity>list("active = true order by sortOrder, code").stream()
                .map(provider -> new EmailProviderView(provider.code, provider.displayName)).toList();
    }

    @Transactional
    public WorkAccountView create(String rawToken, UUID requestedTenantId, String emailId,
                                  String providerCode, UUID workItemId) {
        ApplicationUserEntity actor = administrator(rawToken);
        TenantEntity tenant = targetTenant(actor, requestedTenantId);
        String normalizedEmail = normalizeEmail(emailId);
        ensureUnique(tenant.id, normalizedEmail, null);
        WorkItemDefinitionEntity workItem = workItems.requireEffective(workItemId, tenant.id);
        EmailProviderEntity provider = requireProvider(providerCode);

        Instant now = Instant.now();
        WorkAccountEntity account = new WorkAccountEntity();
        account.tenant = tenant;
        account.emailId = emailId.trim();
        account.normalizedEmailId = normalizedEmail;
        account.workItemDefinition = workItem;
        account.legacyWorkItemType = workItem.type;
        account.provider = provider;
        account.createdAt = now;
        account.updatedAt = now;
        Panache.getEntityManager().persist(account);
        Panache.getEntityManager().flush();
        EmailPollingConfigEntity polling = new EmailPollingConfigEntity();
        polling.workAccount = account;
        polling.emailId = account.emailId;
        polling.provider = provider;
        polling.createdAt = now;
        polling.updatedAt = now;
        Panache.getEntityManager().persist(polling);
        workflows.initialize(account.id, account.emailId, tenant, workItem);
        return WorkAccountView.from(account, polling);
    }

    @Transactional
    public WorkAccountView update(String rawToken, UUID accountId, String emailId,
                                  String providerCode, UUID workItemId) {
        ApplicationUserEntity actor = administrator(rawToken);
        WorkAccountEntity account = manageable(actor, accountId);
        String normalizedEmail = normalizeEmail(emailId);
        ensureUnique(account.tenant.id, normalizedEmail, account.id);
        WorkItemDefinitionEntity workItem = workItems.requireEffective(workItemId, account.tenant.id);
        EmailProviderEntity provider = requireProvider(providerCode);
        EmailPollingConfigEntity polling = polling(account.id);

        if (!account.normalizedEmailId.equals(normalizedEmail) || !account.provider.code.equals(provider.code)) {
            clearConnection(account, polling);
        }
        workflows.changeDefinition(account.id, emailId.trim(), workItem);
        account.emailId = emailId.trim();
        account.normalizedEmailId = normalizedEmail;
        account.provider = provider;
        account.workItemDefinition = workItem;
        account.legacyWorkItemType = workItem.type;
        account.updatedAt = Instant.now();
        polling.emailId = account.emailId;
        polling.provider = provider;
        polling.updatedAt = account.updatedAt;
        return WorkAccountView.from(account, polling);
    }

    @Transactional
    public WorkAccountTarget requireManageable(String rawToken, UUID accountId) {
        ApplicationUserEntity actor = administrator(rawToken);
        WorkAccountEntity account = manageable(actor, accountId);
        if (!account.tenant.active) throw new BadRequestException("Tenant is inactive");
        return new WorkAccountTarget(account.id, account.emailId, account.provider.code);
    }

    @Transactional
    public WorkAccountView completeGmailConnection(UUID accountId, String connectedEmailId,
                                                   String accessToken, String refreshToken,
                                                   Instant accessTokenExpiresAt) {
        WorkAccountEntity account = WorkAccountEntity.findById(accountId);
        if (account == null) throw new NotFoundException("Work account not found");
        account.tenant = TenantEntity.findById(account.tenant.id);
        account.workItemDefinition = WorkItemDefinitionEntity.findById(account.workItemDefinition.id);
        account.provider = EmailProviderEntity.findById(account.provider.code);
        if (!"GOOGLE".equals(account.provider.code)) {
            throw new BadRequestException("Work account provider is not GOOGLE");
        }
        if (!account.normalizedEmailId.equals(normalizeEmail(connectedEmailId))) {
            throw new BadRequestException("The selected Gmail account does not match " + account.emailId);
        }
        if ((refreshToken == null || refreshToken.isBlank())
                && (account.refreshToken == null || account.refreshToken.isBlank())) {
            throw new BadRequestException("Google did not return an offline refresh token; reconnect with consent");
        }

        Instant now = Instant.now();
        if (refreshToken != null && !refreshToken.isBlank()) account.refreshToken = refreshToken;
        account.updatedAt = now;
        EmailPollingConfigEntity polling = polling(account.id);
        polling.emailId = account.emailId;
        polling.provider = account.provider;
        polling.accessToken = accessToken;
        polling.accessTokenExpiresAt = accessTokenExpiresAt;
        polling.nextRefreshAt = now;
        polling.updatedAt = now;
        return WorkAccountView.from(account, polling);
    }

    private ApplicationUserEntity administrator(String rawToken) {
        ApplicationUserEntity actor = auth.requireEntity(rawToken);
        auth.requireCompletedPasswordChange(actor);
        if (actor.role != UserRole.GLOBAL_ADMIN && actor.role != UserRole.ADMIN) {
            throw new ForbiddenException("Administrator role required");
        }
        return actor;
    }

    private WorkAccountEntity manageable(ApplicationUserEntity actor, UUID accountId) {
        WorkAccountEntity account = WorkAccountEntity.findById(accountId);
        if (account == null) throw new NotFoundException("Work account not found");
        initializeReferences(account);
        if (actor.role != UserRole.GLOBAL_ADMIN && !actor.tenant.id.equals(account.tenant.id)) {
            throw new ForbiddenException("Work account is outside your tenant");
        }
        return account;
    }

    private void initializeReferences(WorkAccountEntity account) {
        account.tenant = TenantEntity.findById(account.tenant.id);
        account.workItemDefinition = WorkItemDefinitionEntity.findById(account.workItemDefinition.id);
        account.provider = EmailProviderEntity.findById(account.provider.code);
    }

    private TenantEntity targetTenant(ApplicationUserEntity actor, UUID requestedTenantId) {
        UUID tenantId = actor.role == UserRole.GLOBAL_ADMIN ? requestedTenantId : actor.tenant.id;
        if (actor.role != UserRole.GLOBAL_ADMIN) assertOwnTenant(actor, requestedTenantId);
        if (tenantId == null) throw new BadRequestException("tenantId is required for GLOBAL_ADMIN");
        TenantEntity tenant = TenantEntity.findById(tenantId);
        if (tenant == null) throw new NotFoundException("Tenant not found");
        if (!tenant.active) throw new BadRequestException("Tenant is inactive");
        return tenant;
    }

    private void assertOwnTenant(ApplicationUserEntity actor, UUID requestedTenantId) {
        if (requestedTenantId != null && !actor.tenant.id.equals(requestedTenantId)) {
            throw new ForbiddenException("Tenant is outside your administration scope");
        }
    }

    private void ensureUnique(UUID tenantId, String normalizedEmail, UUID excludedId) {
        long count = excludedId == null
                ? WorkAccountEntity.count("tenant.id = ?1 and normalizedEmailId = ?2", tenantId, normalizedEmail)
                : WorkAccountEntity.count("tenant.id = ?1 and normalizedEmailId = ?2 and id <> ?3",
                        tenantId, normalizedEmail, excludedId);
        if (count > 0) throw new WebApplicationException("Email already exists for this tenant", 409);
    }

    private static void clearConnection(WorkAccountEntity account, EmailPollingConfigEntity polling) {
        account.refreshToken = null;
        polling.accessToken = null;
        polling.accessTokenExpiresAt = null;
        polling.nextRefreshAt = null;
    }

    private static String normalizeEmail(String emailId) {
        return emailId.trim().toLowerCase(Locale.ROOT);
    }

    private WorkAccountView view(WorkAccountEntity account) {
        return WorkAccountView.from(account, polling(account.id));
    }

    private EmailPollingConfigEntity polling(UUID accountId) {
        EmailPollingConfigEntity config = EmailPollingConfigEntity.find("workAccount.id", accountId).firstResult();
        if (config == null) throw new IllegalStateException("Email polling configuration is missing");
        config.provider = EmailProviderEntity.findById(config.provider.code);
        return config;
    }

    private EmailProviderEntity requireProvider(String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        EmailProviderEntity provider = EmailProviderEntity.find("code = ?1 and active = true", code).firstResult();
        if (provider == null) throw new BadRequestException("Unsupported or inactive email provider");
        return provider;
    }

    public record WorkAccountTarget(UUID id, String emailId, String provider) {}
    public record EmailProviderView(String code, String displayName) {}
}
