package com.casiq.workaccount.gmail;

import com.casiq.workaccount.core.polling.EmailProviderConnector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class GmailEmailProviderConnector implements EmailProviderConnector {
    private static final String PROVIDER_CODE = "GOOGLE";
    private static final Logger LOG = Logger.getLogger(GmailEmailProviderConnector.class);

    @Inject @RestClient GoogleOAuthClient googleOAuth;
    @Inject @RestClient GmailMailboxClient gmail;
    @Inject ObjectMapper json;
    @ConfigProperty(name = "casiq.google.client-id") String clientId;
    @ConfigProperty(name = "casiq.google.client-secret") String clientSecret;
    @ConfigProperty(name = "casiq.gmail.polling-page-size") int pageSize;
    @ConfigProperty(name = "casiq.gmail.max-attachment-bytes") long maxAttachmentBytes;
    private static final Pattern EMAIL_ADDRESS =
            Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public PollResult fetch(PollRequest request) {
        LOG.debugf("Starting Gmail fetch workAccountId=%s after=%s pageSize=%d",
                request.workAccountId(), request.after(), pageSize);
        TokenState token = validAccessToken(request, Instant.now());
        String authorization = "Bearer " + token.accessToken();
        List<EmailMessage> messages = new ArrayList<>();
        String pageToken = null;
        do {
            GmailMailboxClient.MessageList page = gmail.messages(
                    authorization,
                    "after:" + request.after().getEpochSecond(),
                    Math.max(1, pageSize),
                    pageToken);
            List<GmailMailboxClient.MessageReference> references =
                    page == null || page.messages() == null ? List.of() : page.messages();
            LOG.debugf("Gmail list page workAccountId=%s messages=%d hasNextPage=%s",
                    request.workAccountId(), references.size(),
                    page != null && page.nextPageToken() != null && !page.nextPageToken().isBlank());
            for (GmailMailboxClient.MessageReference reference : references) {
                if (reference == null || reference.id() == null) continue;
                GmailMailboxClient.GmailMessage message =
                        gmail.message(authorization, reference.id(), "full");
                if (message != null && message.id() != null) {
                    messages.add(toMessage(message, authorization));
                }
            }
            pageToken = page == null ? null : page.nextPageToken();
        } while (pageToken != null && !pageToken.isBlank());

        LOG.infof("Gmail fetch completed workAccountId=%s messages=%d",
                request.workAccountId(), messages.size());
        return new PollResult(
                List.copyOf(messages),
                token.accessToken(),
                token.accessTokenExpiresAt(),
                token.refreshToken());
    }

    @Override
    public ReadResult read(ReadRequest request) {
        Instant now = Instant.now();
        TokenState token = validAccessToken(
                request.workAccountId(), request.refreshToken(), request.accessToken(),
                request.accessTokenExpiresAt(), now);
        GmailMailboxClient.GmailMessage message = gmail.message(
                "Bearer " + token.accessToken(), request.providerMessageId(), "full");
        if (message == null || message.id() == null) {
            throw new IllegalStateException("Gmail message is unavailable");
        }
        LOG.debugf("Read Gmail message directly workAccountId=%s providerMessageId=%s",
                request.workAccountId(), request.providerMessageId());
        return new ReadResult(
                toMessage(message, "Bearer " + token.accessToken()),
                token.accessToken(),
                token.accessTokenExpiresAt(),
                token.refreshToken());
    }

    @Override
    public ReplyResult reply(ReplyRequest request) {
        Instant now = Instant.now();
        TokenState token = validAccessToken(
                request.workAccountId(), request.refreshToken(), request.accessToken(),
                request.accessTokenExpiresAt(), now);
        String authorization = "Bearer " + token.accessToken();
        String recipient = emailAddress(request.recipient());
        String rawMessage = rawReply(
                request.emailId(), recipient, request.subject(), request.inReplyTo(),
                request.referenceIds(), request.htmlBody(), request.attachments());
        GmailMailboxClient.GmailMessage sent = gmail.send(
                authorization,
                new GmailMailboxClient.SendMessage(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(
                                rawMessage.getBytes(StandardCharsets.UTF_8)),
                        request.providerThreadId()));
        if (sent == null || sent.id() == null) {
            throw new IllegalStateException("Gmail did not return the sent message");
        }
        GmailMailboxClient.GmailMessage stored = gmail.message(authorization, sent.id(), "full");
        LOG.infof("Sent Gmail reply workAccountId=%s providerMessageId=%s threadId=%s",
                request.workAccountId(), sent.id(), sent.threadId());
        return new ReplyResult(
                toMessage(stored == null ? sent : stored, authorization),
                token.accessToken(), token.accessTokenExpiresAt(), token.refreshToken());
    }

    private TokenState validAccessToken(PollRequest request, Instant now) {
        return validAccessToken(request.workAccountId(), request.refreshToken(),
                request.accessToken(), request.accessTokenExpiresAt(), now);
    }

    private TokenState validAccessToken(
            java.util.UUID workAccountId,
            String refreshTokenValue,
            String accessToken,
            Instant accessTokenExpiresAt,
            Instant now) {
        if (accessToken != null
                && accessTokenExpiresAt != null
                && accessTokenExpiresAt.isAfter(now.plusSeconds(60))) {
            return new TokenState(
                    accessToken,
                    accessTokenExpiresAt,
                    refreshTokenValue);
        }
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            throw new IllegalStateException("Google work account has no refresh token");
        }
        GoogleOAuthClient.GoogleTokenResponse refreshed =
                googleOAuth.refresh(clientId, clientSecret, refreshTokenValue, "refresh_token");
        if (refreshed == null || refreshed.accessToken() == null || refreshed.accessToken().isBlank()) {
            throw new IllegalStateException("Google did not return an access token");
        }
        String refreshToken = refreshed.refreshToken() == null || refreshed.refreshToken().isBlank()
                ? refreshTokenValue
                : refreshed.refreshToken();
        LOG.infof("Refreshed Google access token workAccountId=%s expiresInSeconds=%d rotatedRefreshToken=%s",
                workAccountId, refreshed.expiresIn(),
                !Objects.equals(refreshToken, refreshTokenValue));
        return new TokenState(
                refreshed.accessToken(),
                now.plusSeconds(refreshed.expiresIn()),
                refreshToken);
    }

    private EmailMessage toMessage(
            GmailMailboxClient.GmailMessage message,
            String authorization) {
        MessageContent content = content(
                message.id(), message.payload(), authorization);
        return new EmailMessage(
                message.id(),
                message.threadId(),
                header(message, "Message-ID"),
                header(message, "In-Reply-To"),
                header(message, "References"),
                header(message, "Subject"),
                header(message, "From"),
                recipients(message),
                internalDate(message.internalDate()),
                message.snippet(),
                json(message),
                content.text(),
                content.html(),
                attachments(message, authorization));
    }

    private List<EmailAttachment> attachments(
            GmailMailboxClient.GmailMessage message,
            String authorization) {
        List<EmailAttachment> attachments = new ArrayList<>();
        collectAttachments(message.id(), message.payload(), authorization, attachments);
        return List.copyOf(attachments);
    }

    private void collectAttachments(
            String messageId,
            GmailMailboxClient.MessagePayload part,
            String authorization,
            List<EmailAttachment> target) {
        if (part == null) return;
        if (isAttachment(part) && part.body() != null) {
            long declaredSize = part.body().size() == null ? 0 : part.body().size();
            if (declaredSize > maxAttachmentBytes) {
                throw new IllegalStateException(
                        "Gmail attachment exceeds the configured maximum size");
            }
            GmailMailboxClient.MessageBody source = part.body();
            if ((source.data() == null || source.data().isBlank())
                    && source.attachmentId() != null) {
                source = gmail.attachment(
                        authorization, messageId, source.attachmentId());
            }
            byte[] data = decodeBytes(source == null ? null : source.data());
            if (data.length > maxAttachmentBytes) {
                throw new IllegalStateException(
                        "Gmail attachment exceeds the configured maximum size");
            }
            String attachmentId = attachmentStorageKey(
                    messageId, part.body().attachmentId(), target.size());
            target.add(new EmailAttachment(
                    attachmentId,
                    attachmentFilename(part, target.size()),
                    part.mimeType(),
                    data));
        }
        if (part.parts() != null) {
            for (GmailMailboxClient.MessagePayload child : part.parts()) {
                collectAttachments(messageId, child, authorization, target);
            }
        }
    }

    private MessageContent content(
            String messageId,
            GmailMailboxClient.MessagePayload payload,
            String authorization) {
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        collectContent(messageId, payload, authorization, text, html);
        String plainText = text.isEmpty() ? null : text.toString();
        String renderedHtml = html.isEmpty() ? textAsHtml(plainText) : html.toString();
        return new MessageContent(
                plainText,
                renderedHtml);
    }

    private void collectContent(
            String messageId,
            GmailMailboxClient.MessagePayload payload,
            String authorization,
            StringBuilder text,
            StringBuilder html) {
        if (payload == null) return;
        if (!isAttachment(payload) && payload.body() != null) {
            String data = payload.body().data();
            if ((data == null || data.isBlank())
                    && payload.body().attachmentId() != null
                    && ("text/plain".equalsIgnoreCase(payload.mimeType())
                    || "text/html".equalsIgnoreCase(payload.mimeType()))) {
                GmailMailboxClient.MessageBody fetched = gmail.attachment(
                        authorization, messageId, payload.body().attachmentId());
                data = fetched == null ? null : fetched.data();
            }
            String decoded = decode(data);
            if ("text/plain".equalsIgnoreCase(payload.mimeType())) append(text, decoded);
            if ("text/html".equalsIgnoreCase(payload.mimeType())) append(html, decoded);
        }
        if (payload.parts() != null) {
            payload.parts().forEach(part ->
                    collectContent(messageId, part, authorization, text, html));
        }
    }

    private static boolean isAttachment(
            GmailMailboxClient.MessagePayload part) {
        if (part == null) return false;
        if (part.filename() != null && !part.filename().isBlank()) return true;
        if (part.headers() == null) return false;
        return part.headers().stream()
                .filter(header -> "Content-Disposition".equalsIgnoreCase(header.name()))
                .map(GmailMailboxClient.MessageHeader::value)
                .filter(Objects::nonNull)
                .anyMatch(value -> value.trim().toLowerCase(java.util.Locale.ROOT)
                        .startsWith("attachment"));
    }

    private static String attachmentFilename(
            GmailMailboxClient.MessagePayload part,
            int index) {
        if (part.filename() != null && !part.filename().isBlank()) {
            return part.filename();
        }
        return "attachment-" + (index + 1);
    }

    private static String attachmentStorageKey(
            String messageId,
            String providerAttachmentId,
            int index) {
        String source = messageId + "\u0000"
                + (providerAttachmentId == null || providerAttachmentId.isBlank()
                ? "part-" + index
                : providerAttachmentId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "gmail:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String decode(String data) {
        if (data == null || data.isBlank()) return null;
        try {
            return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidData) {
            return null;
        }
    }

    private static byte[] decodeBytes(String data) {
        if (data == null || data.isBlank()) return new byte[0];
        try {
            return Base64.getUrlDecoder().decode(data);
        } catch (IllegalArgumentException invalidData) {
            throw new IllegalStateException("Gmail returned invalid attachment data", invalidData);
        }
    }

    private static String emailAddress(String header) {
        Matcher matcher = EMAIL_ADDRESS.matcher(header == null ? "" : header);
        if (!matcher.find()) throw new IllegalArgumentException("Original sender has no valid email address");
        return matcher.group();
    }

    private static String rawReply(
            String from,
            String to,
            String subject,
            String inReplyTo,
            String referenceIds,
            String htmlBody,
            List<OutgoingAttachment> attachments) {
        String safeFrom = headerValue(from);
        String safeTo = headerValue(to);
        String encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(
                (subject == null ? "" : subject).getBytes(StandardCharsets.UTF_8)) + "?=";
        StringBuilder message = new StringBuilder()
                .append("From: ").append(safeFrom).append("\r\n")
                .append("To: ").append(safeTo).append("\r\n")
                .append("Subject: ").append(encodedSubject).append("\r\n");
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            message.append("In-Reply-To: ").append(headerValue(inReplyTo)).append("\r\n");
        }
        String references = mergeReferences(referenceIds, inReplyTo);
        if (!references.isBlank()) {
            message.append("References: ").append(headerValue(references)).append("\r\n");
        }
        message.append("MIME-Version: 1.0\r\n");
        List<OutgoingAttachment> files = attachments == null ? List.of() : attachments;
        if (files.isEmpty()) {
            return message
                    .append("Content-Type: text/html; charset=UTF-8\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n")
                    .append(mimeBase64((htmlBody == null ? "" : htmlBody)
                            .getBytes(StandardCharsets.UTF_8)))
                    .append("\r\n")
                    .toString();
        }

        String boundary = "casiq-" + UUID.randomUUID();
        message.append("Content-Type: multipart/mixed; boundary=\"")
                .append(boundary)
                .append("\"\r\n\r\n")
                .append("--").append(boundary).append("\r\n")
                .append("Content-Type: text/html; charset=UTF-8\r\n")
                .append("Content-Transfer-Encoding: base64\r\n\r\n")
                .append(mimeBase64((htmlBody == null ? "" : htmlBody)
                        .getBytes(StandardCharsets.UTF_8)))
                .append("\r\n");
        for (OutgoingAttachment attachment : files) {
            String contentType = safeContentType(attachment.contentType());
            String filename = encodedFilename(attachment.filename());
            message.append("--").append(boundary).append("\r\n")
                    .append("Content-Type: ").append(contentType).append("\r\n")
                    .append("Content-Disposition: attachment; filename*=UTF-8''")
                    .append(filename).append("\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n")
                    .append(mimeBase64(attachment.content()))
                    .append("\r\n");
        }
        return message.append("--").append(boundary).append("--\r\n").toString();
    }

    private static String mimeBase64(byte[] content) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(content == null ? new byte[0] : content);
    }

    private static String safeContentType(String value) {
        String candidate = headerValue(value);
        return candidate.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
                ? candidate
                : "application/octet-stream";
    }

    private static String encodedFilename(String value) {
        return java.net.URLEncoder.encode(
                        value == null ? "attachment" : value,
                        StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String mergeReferences(String references, String messageId) {
        String left = references == null ? "" : references.trim();
        String right = messageId == null ? "" : messageId.trim();
        if (right.isBlank() || left.contains(right)) return left;
        return left.isBlank() ? right : left + " " + right;
    }

    private static String headerValue(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "").trim();
    }

    private static void append(StringBuilder target, String value) {
        if (value == null || value.isBlank()) return;
        if (!target.isEmpty()) target.append("\n\n");
        target.append(value);
    }

    private static String textAsHtml(String text) {
        if (text == null) return null;
        return "<pre style=\"white-space:pre-wrap\">"
                + text.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                + "</pre>";
    }

    private static String header(GmailMailboxClient.GmailMessage message, String name) {
        if (message.payload() == null || message.payload().headers() == null) return null;
        return message.payload().headers().stream()
                .filter(header -> name.equalsIgnoreCase(header.name()))
                .map(GmailMailboxClient.MessageHeader::value)
                .findFirst()
                .orElse(null);
    }

    private static String recipients(GmailMailboxClient.GmailMessage message) {
        return java.util.stream.Stream.of(
                        header(message, "To"),
                        header(message, "Cc"),
                        header(message, "Bcc"))
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
    }

    private static Instant internalDate(String milliseconds) {
        try {
            return milliseconds == null ? null : Instant.ofEpochMilli(Long.parseLong(milliseconds));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String json(GmailMailboxClient.GmailMessage message) {
        try {
            return json.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize Gmail message", exception);
        }
    }

    private record TokenState(
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken) {
    }

    private record MessageContent(String text, String html) {
    }
}
