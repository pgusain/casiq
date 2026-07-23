package com.casiq.usermanagement.api;

import com.casiq.usermanagement.persistence.TenantEntity;

import java.time.Instant;
import java.util.UUID;

public record TenantView(
        UUID id,
        String companyCode,
        String displayName,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static TenantView from(TenantEntity tenant) {
        return new TenantView(tenant.id, tenant.companyCode, tenant.displayName,
                tenant.active, tenant.createdAt, tenant.updatedAt);
    }
}
