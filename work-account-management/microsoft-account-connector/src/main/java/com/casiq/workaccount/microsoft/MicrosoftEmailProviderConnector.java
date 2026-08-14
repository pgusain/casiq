package com.casiq.workaccount.microsoft;

import com.casiq.workaccount.core.polling.EmailProviderConnector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

@ApplicationScoped
public class MicrosoftEmailProviderConnector implements EmailProviderConnector {
    private static final Logger LOG =
            Logger.getLogger(MicrosoftEmailProviderConnector.class);
    private static final String PROVIDER_CODE = "MICROSOFT";
    private static final String PREFER =
            "IdType=\"ImmutableId\", outlook.body-content-type=\"html\"";
    private static final String MESSAGE_FIELDS =
            "id,conversationId,internetMessageId,subject,from,toRecipients,ccRecipients,"
                    + "bccRecipients,receivedDateTime,sentDateTime,bodyPreview,body,"
                    + "hasAttachments,internetMessageHeaders";

    @Inject @RestClient MicrosoftOAuthClient microsoftOAuth;
    @Inject @RestClient MicrosoftGraphClient graph;
    @Inject ObjectMapper json;
    @ConfigProperty(name = "casiq.microsoft.client-id") String clientId;
    @ConfigProperty(name = "casiq.microsoft.client-secret") String clientSecret;
    @ConfigProperty(name = "casiq.microsoft.tenant") String tenant;
    @ConfigProperty(name = "casiq.microsoft.polling-page-size") int pageSize;
    @ConfigProperty(name = "casiq.microsoft.max-attachment-bytes") long maxAttachmentBytes;

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public PollResult fetch(PollRequest request) {
        TokenState token = validAccessToken(
                request.workAccountId(), request.refreshToken(), request.accessToken(),
                request.accessTokenExpiresAt(), Instant.now());
        String authorization = "Bearer " + token.accessToken();
        String filter = "receivedDateTime ge " + request.after();
        MicrosoftGraphClient.MessagePage page = graph.inboxMessages(
                authorization, PREFER, filter, "receivedDateTime asc",
                MESSAGE_FIELDS, Math.max(1, Math.min(1000, pageSize)));
        List<EmailMessage> messages = new ArrayList<>();
        while (page != null) {
            if (page.value() != null) {
                for (MicrosoftGraphClient.GraphMessage message : page.value()) {
                    if (message != null && message.id() != null) {
                        messages.add(toMessage(message, authorization));
                    }
                }
            }
            if (page.nextLink() == null || page.nextLink().isBlank()) break;
            page = graph.messagesAt(authorization, PREFER, page.nextLink());
        }
        LOG.infof("Microsoft 365 fetch completed workAccountId=%s messages=%d",
                request.workAccountId(), messages.size());
        return new PollResult(List.copyOf(messages), token.accessToken(),
                token.accessTokenExpiresAt(), token.refreshToken());
    }

    @Override
    public ReadResult read(ReadRequest request) {
        TokenState token = validAccessToken(
                request.workAccountId(), request.refreshToken(), request.accessToken(),
                request.accessTokenExpiresAt(), Instant.now());
        String authorization = "Bearer " + token.accessToken();
        MicrosoftGraphClient.GraphMessage message = graph.message(
                authorization, PREFER, request.providerMessageId(), MESSAGE_FIELDS);
        if (message == null || message.id() == null) {
            throw new IllegalStateException("Microsoft 365 message is unavailable");
        }
        return new ReadResult(toMessage(message, authorization), token.accessToken(),
                token.accessTokenExpiresAt(), token.refreshToken());
    }

    @Override
    public ReplyResult reply(ReplyRequest request) {
        if (request.sourceProviderMessageId() == null
                || request.sourceProviderMessageId().isBlank()) {
            throw new IllegalArgumentException(
                    "Microsoft 365 reply requires the source provider message ID");
        }
        TokenState token = validAccessToken(
                request.workAccountId(), request.refreshToken(), request.accessToken(),
                request.accessTokenExpiresAt(), Instant.now());
        String authorization = "Bearer " + token.accessToken();
        String raw = rawReply(
                request.emailId(), request.recipient(), request.subject(),
                request.inReplyTo(), request.referenceIds(), request.htmlBody(),
                request.attachments());
        MicrosoftGraphClient.GraphMessage draft = graph.createReply(
                authorization, PREFER, request.sourceProviderMessageId(),
                Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        if (draft == null || draft.id() == null) {
            throw new IllegalStateException("Microsoft Graph did not return a reply draft");
        }
        graph.send(authorization, PREFER, draft.id());
        EmailMessage sent = new EmailMessage(
                draft.id(),
                firstNonBlank(draft.conversationId(), request.providerThreadId()),
                draft.internetMessageId(),
                request.inReplyTo(),
                mergeReferences(request.referenceIds(), request.inReplyTo()),
                firstNonBlank(draft.subject(), replySubject(request.subject())),
                request.emailId(),
                request.recipient(),
                Instant.now(),
                plainText(request.htmlBody()),
                json(draft),
                plainText(request.htmlBody()),
                request.htmlBody(),
                List.of());
        LOG.infof("Sent Microsoft 365 reply workAccountId=%s providerMessageId=%s conversationId=%s",
                request.workAccountId(), draft.id(), sent.providerThreadId());
        return new ReplyResult(sent, token.accessToken(),
                token.accessTokenExpiresAt(), token.refreshToken());
    }

    private TokenState validAccessToken(
            Long workAccountId,
            String refreshTokenValue,
            String accessToken,
            Instant accessTokenExpiresAt,
            Instant now) {
        if (accessToken != null && accessTokenExpiresAt != null
                && accessTokenExpiresAt.isAfter(now.plusSeconds(60))) {
            return new TokenState(accessToken, accessTokenExpiresAt, refreshTokenValue);
        }
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            throw new IllegalStateException("Microsoft work account has no refresh token");
        }
        MicrosoftOAuthClient.MicrosoftTokenResponse refreshed = microsoftOAuth.refresh(
                tenant, clientId, clientSecret, refreshTokenValue,
                "refresh_token", MicrosoftOAuthService.SCOPE);
        if (refreshed == null || refreshed.accessToken() == null
                || refreshed.accessToken().isBlank()) {
            throw new IllegalStateException("Microsoft did not return an access token");
        }
        String refreshToken = refreshed.refreshToken() == null
                || refreshed.refreshToken().isBlank()
                ? refreshTokenValue : refreshed.refreshToken();
        LOG.infof("Refreshed Microsoft access token workAccountId=%s rotatedRefreshToken=%s",
                workAccountId, !Objects.equals(refreshToken, refreshTokenValue));
        return new TokenState(refreshed.accessToken(),
                now.plusSeconds(refreshed.expiresIn()), refreshToken);
    }

    private EmailMessage toMessage(
            MicrosoftGraphClient.GraphMessage message,
            String authorization) {
        String html = message.body() == null ? null : message.body().content();
        String text = message.body() != null
                && "text".equalsIgnoreCase(message.body().contentType())
                ? message.body().content() : plainText(html);
        return new EmailMessage(
                message.id(),
                message.conversationId(),
                message.internetMessageId(),
                header(message, "In-Reply-To"),
                header(message, "References"),
                message.subject(),
                recipient(message.from()),
                recipients(message),
                message.receivedDateTime() == null
                        ? message.sentDateTime() : message.receivedDateTime(),
                message.bodyPreview(),
                json(message),
                text,
                html,
                attachments(message, authorization));
    }

    private List<EmailAttachment> attachments(
            MicrosoftGraphClient.GraphMessage message,
            String authorization) {
        if (!Boolean.TRUE.equals(message.hasAttachments())) return List.of();
        MicrosoftGraphClient.AttachmentPage page =
                graph.attachments(authorization, PREFER, message.id());
        if (page == null || page.value() == null) return List.of();
        List<EmailAttachment> result = new ArrayList<>();
        for (MicrosoftGraphClient.GraphAttachment attachment : page.value()) {
            if (attachment == null
                    || attachment.odataType() == null
                    || !attachment.odataType().endsWith("fileAttachment")
                    || Boolean.TRUE.equals(attachment.isInline())) {
                continue;
            }
            if (attachment.size() != null
                    && attachment.size() > maxAttachmentBytes) {
                throw new IllegalStateException(
                        "Microsoft 365 attachment exceeds the configured maximum size");
            }
            byte[] content;
            try {
                content = Base64.getDecoder().decode(
                        attachment.contentBytes() == null ? "" : attachment.contentBytes());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "Microsoft Graph returned invalid attachment data", exception);
            }
            if (content.length > maxAttachmentBytes) {
                throw new IllegalStateException(
                        "Microsoft 365 attachment exceeds the configured maximum size");
            }
            result.add(new EmailAttachment(
                    attachmentStorageKey(message.id(), attachment.id(), result.size()),
                    firstNonBlank(attachment.name(), "attachment-" + (result.size() + 1)),
                    attachment.contentType(),
                    content));
        }
        return List.copyOf(result);
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to serialize Microsoft Graph message", exception);
        }
    }

    private static String header(
            MicrosoftGraphClient.GraphMessage message,
            String name) {
        if (message.internetMessageHeaders() == null) return null;
        return message.internetMessageHeaders().stream()
                .filter(header -> name.equalsIgnoreCase(header.name()))
                .map(MicrosoftGraphClient.InternetMessageHeader::value)
                .findFirst().orElse(null);
    }

    private static String recipients(MicrosoftGraphClient.GraphMessage message) {
        return Stream.of(message.toRecipients(), message.ccRecipients(), message.bccRecipients())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(MicrosoftEmailProviderConnector::recipient)
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
    }

    private static String recipient(MicrosoftGraphClient.Recipient recipient) {
        if (recipient == null || recipient.emailAddress() == null) return null;
        String address = recipient.emailAddress().address();
        String name = recipient.emailAddress().name();
        return name == null || name.isBlank() ? address : name + " <" + address + ">";
    }

    private static String rawReply(
            String from,
            String to,
            String subject,
            String inReplyTo,
            String references,
            String html,
            List<OutgoingAttachment> attachments) {
        String boundary = "casiq-" + UUID.randomUUID();
        StringBuilder raw = new StringBuilder()
                .append("From: ").append(safeHeader(from)).append("\r\n")
                .append("To: ").append(safeHeader(to)).append("\r\n")
                .append("Subject: ").append(encodedSubject(replySubject(subject))).append("\r\n");
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            raw.append("In-Reply-To: ").append(safeHeader(inReplyTo)).append("\r\n");
        }
        String merged = mergeReferences(references, inReplyTo);
        if (!merged.isBlank()) {
            raw.append("References: ").append(safeHeader(merged)).append("\r\n");
        }
        raw.append("MIME-Version: 1.0\r\n");
        List<OutgoingAttachment> files = attachments == null ? List.of() : attachments;
        if (files.isEmpty()) {
            return raw.append("Content-Type: text/html; charset=UTF-8\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n")
                    .append(mimeBase64((html == null ? "" : html)
                            .getBytes(StandardCharsets.UTF_8)))
                    .append("\r\n").toString();
        }
        raw.append("Content-Type: multipart/mixed; boundary=\"")
                .append(boundary).append("\"\r\n\r\n")
                .append("--").append(boundary).append("\r\n")
                .append("Content-Type: text/html; charset=UTF-8\r\n")
                .append("Content-Transfer-Encoding: base64\r\n\r\n")
                .append(mimeBase64((html == null ? "" : html)
                        .getBytes(StandardCharsets.UTF_8))).append("\r\n");
        for (OutgoingAttachment attachment : files) {
            raw.append("--").append(boundary).append("\r\n")
                    .append("Content-Type: ")
                    .append(safeContentType(attachment.contentType())).append("\r\n")
                    .append("Content-Disposition: attachment; filename*=UTF-8''")
                    .append(encodedFilename(attachment.filename())).append("\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n")
                    .append(mimeBase64(attachment.content())).append("\r\n");
        }
        return raw.append("--").append(boundary).append("--\r\n").toString();
    }

    private static String mimeBase64(byte[] content) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(content == null ? new byte[0] : content);
    }

    private static String encodedFilename(String value) {
        return java.net.URLEncoder.encode(
                value == null ? "attachment" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String attachmentStorageKey(
            String messageId,
            String attachmentId,
            int index) {
        String source = messageId + "\u0000"
                + (attachmentId == null || attachmentId.isBlank()
                ? "attachment-" + index : attachmentId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "microsoft:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safeContentType(String value) {
        String candidate = safeHeader(value);
        return candidate.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
                ? candidate : "application/octet-stream";
    }

    private static String safeHeader(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    private static String encodedSubject(String value) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(
                safeHeader(value).getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String mergeReferences(String references, String messageId) {
        String left = references == null ? "" : references.trim();
        String right = messageId == null ? "" : messageId.trim();
        if (right.isBlank() || left.contains(right)) return left;
        return left.isBlank() ? right : left + " " + right;
    }

    private static String replySubject(String subject) {
        String value = subject == null ? "" : subject.trim();
        return value.regionMatches(true, 0, "Re:", 0, 3) ? value : "Re: " + value;
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String plainText(String html) {
        if (html == null) return null;
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .trim();
    }

    private record TokenState(
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken) {
    }
}
