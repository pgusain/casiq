package com.casiq.workaccount.gmail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "gmail-profile")
public interface GmailProfileClient {
    @GET
    @Path("/gmail/v1/users/me/profile")
    @Produces(MediaType.APPLICATION_JSON)
    GmailProfile profile(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GmailProfile(@JsonProperty("emailAddress") String emailAddress) {}
}
