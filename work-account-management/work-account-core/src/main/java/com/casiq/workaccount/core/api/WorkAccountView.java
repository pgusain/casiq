package com.casiq.workaccount.core.api;

import com.casiq.workaccount.core.persistence.WorkAccountEntity;
import com.casiq.workaccount.core.persistence.EmailPollingConfigEntity;

import java.time.Instant;
import java.util.UUID;

public record WorkAccountView(
        UUID id,
        UUID tenantId,
        String companyCode,
        String emailId,
        UUID workItemId,
        String workItemType,
        String workItemDisplayName,
        String provider,
        String providerDisplayName,
        boolean connected,
        Instant accessTokenExpiresAt,
        Instant nextRefreshAt,
        Instant createdAt,
        Instant updatedAt) {

    public static WorkAccountView from(WorkAccountEntity account, EmailPollingConfigEntity polling) {
        return new WorkAccountView(account.id, account.tenant.id, account.tenant.companyCode,
                account.emailId, account.workItemDefinition.id, account.workItemDefinition.type,
                account.workItemDefinition.displayName, account.provider.code, account.provider.displayName,
                account.refreshToken != null && !account.refreshToken.isBlank(),
                polling.accessTokenExpiresAt, polling.nextRefreshAt, account.createdAt, account.updatedAt);
    }
}
