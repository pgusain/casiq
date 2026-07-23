package com.casiq.workaccount.oauth;

public record PkceAuthorization(String state, String codeVerifier, String codeChallenge) {
}
