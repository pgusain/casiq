package com.casiq.usermanagement.domain;

import org.jboss.logging.Logger;

public enum UserRole {
    GLOBAL_ADMIN,
    ADMIN,
    PROCESSOR,
    BASE_USER;

    private static final Logger LOG = Logger.getLogger(UserRole.class);

    public boolean canAdministerUsers() {
        boolean allowed = this == GLOBAL_ADMIN || this == ADMIN;
        LOG.debugf("Checking user-role admin permission role=%s allowed=%s", this, allowed);
        return allowed;
    }
}
