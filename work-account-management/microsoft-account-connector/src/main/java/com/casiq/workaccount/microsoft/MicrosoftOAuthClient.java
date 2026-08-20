package com.casiq.workaccount.microsoft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.logging.Logger;

@RegisterRestClient(configKey = "microsoft-oauth")
public interface MicrosoftOAuthClient {
    Logger LOG = Logger.getLogger(MicrosoftOAuthClient.class);
    @POST
    @Path("/{tenant}/oauth2/v2.0/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    MicrosoftTokenResponse exchange(
            @PathParam("tenant") String tenant,
            @FormParam("client_id") String clientId,
            @FormParam("client_secret") String clientSecret,
            @FormParam("code") String code,
            @FormParam("code_verifier") String codeVerifier,
            @FormParam("grant_type") String grantType,
            @FormParam("redirect_uri") String redirectUri,
            @FormParam("scope") String scope);

    @POST
    @Path("/{tenant}/oauth2/v2.0/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    MicrosoftTokenResponse refresh(
            @PathParam("tenant") String tenant,
            @FormParam("client_id") String clientId,
            @FormParam("client_secret") String clientSecret,
            @FormParam("refresh_token") String refreshToken,
            @FormParam("grant_type") String grantType,
            @FormParam("scope") String scope);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MicrosoftTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn,
            String scope,
            @JsonProperty("token_type") String tokenType) {
    }
}
