package com.casiq.usermanagement.api;

import com.casiq.usermanagement.persistence.ApplicationUserEntity;

import java.time.Instant;
public record UserView(
        Long id,
        Long tenantId,
        String companyCode,
        String username,
        String firstName,
        String lastName,
        String role,
        boolean mustChangePassword,
        boolean active,
        Instant createdAt) {

    public static UserView from(ApplicationUserEntity user) {
        return new UserView(
                user.id,
                user.tenant.id,
                user.tenant.companyCode,
                user.username,
                user.firstName,
                user.lastName,
                user.role.name(),
                user.mustChangePassword,
                user.active,
                user.createdAt);
    }
}
