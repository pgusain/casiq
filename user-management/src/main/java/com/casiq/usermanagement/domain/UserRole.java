package com.casiq.usermanagement.domain;

public enum UserRole {
    GLOBAL_ADMIN,
    ADMIN,
    PROCESSOR,
    BASE_USER;

    public boolean canAdministerUsers() {
        return this == GLOBAL_ADMIN || this == ADMIN;
    }
}
