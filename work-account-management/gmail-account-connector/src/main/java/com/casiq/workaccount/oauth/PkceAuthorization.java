package com.casiq.workaccount.oauth;

import org.jboss.logging.Logger;

public record PkceAuthorization(String state, String codeVerifier, String codeChallenge) {
    private static final Logger LOG = Logger.getLogger(PkceAuthorization.class);

    public PkceAuthorization {
        LOG.debugf("Created PKCE authorization state=%s challengeLength=%d", state, codeChallenge == null ? 0 : codeChallenge.length());
    }
}
