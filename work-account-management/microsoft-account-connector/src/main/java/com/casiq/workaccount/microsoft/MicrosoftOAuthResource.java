package com.casiq.workaccount.microsoft;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/v1/microsoft")
@Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_HTML})
public class MicrosoftOAuthResource {
    @Inject MicrosoftOAuthService microsoft;
    @Inject MicrosoftOAuthCallbackPage callbackPage;

    @GET
    @Path("/callback")
    public Response callback(
            @QueryParam("state") String state,
            @QueryParam("code") String code,
            @QueryParam("error") String error,
            @QueryParam("error_description") String errorDescription,
            @Context HttpHeaders headers) {
        String accept = headers.getHeaderString(HttpHeaders.ACCEPT);
        boolean browser = accept != null && accept.contains(MediaType.TEXT_HTML);
        try {
            if (error != null) {
                throw new BadRequestException(
                        errorDescription == null || errorDescription.isBlank()
                                ? "Microsoft authorization failed: " + error
                                : errorDescription);
            }
            MicrosoftOAuthService.ExchangeResult result = microsoft.exchange(state, code);
            return browser
                    ? Response.ok(
                            callbackPage.success(Map.of("workAccount", result.workAccount())),
                            MediaType.TEXT_HTML).build()
                    : Response.ok(result.workAccount(), MediaType.APPLICATION_JSON).build();
        } catch (BadRequestException exception) {
            if (!browser) throw exception;
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_HTML)
                    .entity(callbackPage.error(exception.getMessage())).build();
        } catch (RuntimeException exception) {
            if (!browser) throw exception;
            return Response.status(Response.Status.BAD_GATEWAY)
                    .type(MediaType.TEXT_HTML)
                    .entity(callbackPage.error(
                            "Microsoft token exchange failed. Check the app registration and redirect URI."))
                    .build();
        }
    }
}
