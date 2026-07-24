package com.casiq.workaccount.gmail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.List;

@RegisterRestClient(configKey = "gmail-mailbox")
public interface GmailMailboxClient {
    @GET
    @Path("/gmail/v1/users/me/messages")
    @Produces(MediaType.APPLICATION_JSON)
    MessageList messages(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @QueryParam("q") String query,
            @QueryParam("maxResults") int maxResults,
            @QueryParam("pageToken") String pageToken);

    @GET
    @Path("/gmail/v1/users/me/messages/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    GmailMessage message(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @PathParam("id") String id,
            @QueryParam("format") String format);

    @GET
    @Path("/gmail/v1/users/me/messages/{messageId}/attachments/{attachmentId}")
    @Produces(MediaType.APPLICATION_JSON)
    MessageBody attachment(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            @PathParam("messageId") String messageId,
            @PathParam("attachmentId") String attachmentId);

    @POST
    @Path("/gmail/v1/users/me/messages/send")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    GmailMessage send(
            @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
            SendMessage message);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageList(List<MessageReference> messages,
                       @JsonProperty("nextPageToken") String nextPageToken) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageReference(String id, @JsonProperty("threadId") String threadId) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record GmailMessage(String id, @JsonProperty("threadId") String threadId,
                        String snippet, @JsonProperty("internalDate") String internalDate,
                        MessagePayload payload) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessagePayload(
            String mimeType,
            String filename,
            List<MessageHeader> headers,
            MessageBody body,
            List<MessagePayload> parts) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageBody(
            @JsonProperty("attachmentId") String attachmentId,
            Long size,
            String data) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessageHeader(String name, String value) {}
    record SendMessage(String raw, @JsonProperty("threadId") String threadId) {}
}
