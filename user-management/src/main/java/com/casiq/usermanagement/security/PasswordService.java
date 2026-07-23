package com.casiq.usermanagement.security;

import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PasswordService {
    @ConfigProperty(name = "casiq.security.bcrypt-cost") int cost;

    public String hash(String plainText) {
        return BcryptUtil.bcryptHash(plainText, cost);
    }

    public boolean matches(String plainText, String hash) {
        try {
            return BcryptUtil.matches(plainText, hash);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
