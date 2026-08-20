package com.casiq.usermanagement.security;

import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PasswordService {
    private static final Logger LOG = Logger.getLogger(PasswordService.class);

    @ConfigProperty(name = "casiq.security.bcrypt-cost") int cost;

    public String hash(String plainText) {
        LOG.debug("Hashing plaintext password");
        return BcryptUtil.bcryptHash(plainText, cost);
    }

    public boolean matches(String plainText, String hash) {
        try {
            boolean matches = BcryptUtil.matches(plainText, hash);
            LOG.debugf("Password match check result=%s", matches);
            return matches;
        } catch (RuntimeException exception) {
            LOG.warn("Password comparison failed", exception);
            return false;
        }
    }
}
