package com.casiq.workaccount.microsoft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.rest.client.reactive.Url;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.time.Instant;
import java.util.List;

@RegisterRestClient(configKey = "microsoft-graph")
public interface MicrosoftGraphClient {
    @GET
    @Path("/v1.0/me")
    @Produces(MediaType.APPLICATION_JSON)
    GraphUser me(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @QueryParam("$select") String select);

    @GET
    @Path("/v1.0/me/mailFolders/inbox/messages")
    @Produces(MediaType.APPLICATION_JSON)
    MessagePage inboxMessages(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @HeaderParam("Prefer") String prefer,
            @QueryParam("$filter") String filter,
            @QueryParam("$orderby") String orderBy,
            @QueryParam("$select") String select,
            @QueryParam("$top") int top);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    MessagePage messagesAt(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @HeaderParam("Prefer") String prefer,
            @Url String nextLink);

    @GET
    @Path("/v1.0/me/messages/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    GraphMessage message(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @HeaderParam("Prefer") String prefer,
            @PathParam("id") String id,
            @QueryParam("$select") String select);

    @GET
    @Path("/v1.0/me/messages/{id}/attachments")
    @Produces(MediaType.APPLICATION_JSON)
    AttachmentPage attachments(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @HeaderParam("Prefer") String prefer,
            @PathParam("id") String id);

    @POST
    @Path("/v1.0/me/messages/{id}/createReply")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    GraphMessage createReply(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @HeaderParam("Prefer") String prefer,
            @PathParam("id") String id,
            String base64MimeContent);

    @POST
    @Path("/v1.0/me/messages/{id}/send")
    void send(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @HeaderParam("Prefer") String prefer,
            @PathParam("id") String id);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GraphUser(String mail, String userPrincipalName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessagePage(
            List<GraphMessage> value,
            @JsonProperty("@odata.nextLink") String nextLink) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GraphMessage(
            String id,
            String conversationId,
            String internetMessageId,
            String subject,
            Recipient from,
            List<Recipient> toRecipients,
            List<Recipient> ccRecipients,
            List<Recipient> bccRecipients,
            Instant receivedDateTime,
            Instant sentDateTime,
            String bodyPreview,
            ItemBody body,
            Boolean hasAttachments,
            List<InternetMessageHeader> internetMessageHeaders) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Recipient(EmailAddress emailAddress) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmailAddress(String name, String address) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItemBody(String contentType, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InternetMessageHeader(String name, String value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AttachmentPage(List<GraphAttachment> value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GraphAttachment(
            String id,
            String name,
            String contentType,
            Integer size,
            Boolean isInline,
            @JsonProperty("@odata.type") String odataType,
            String contentBytes) {
    }
}
