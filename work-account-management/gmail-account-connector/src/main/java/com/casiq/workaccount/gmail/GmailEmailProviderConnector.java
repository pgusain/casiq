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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

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
                    messages.add(toMessage(message));
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

    private TokenState validAccessToken(PollRequest request, Instant now) {
        if (request.accessToken() != null
                && request.accessTokenExpiresAt() != null
                && request.accessTokenExpiresAt().isAfter(now.plusSeconds(60))) {
            return new TokenState(
                    request.accessToken(),
                    request.accessTokenExpiresAt(),
                    request.refreshToken());
        }
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw new IllegalStateException("Google work account has no refresh token");
        }
        GoogleOAuthClient.GoogleTokenResponse refreshed =
                googleOAuth.refresh(clientId, clientSecret, request.refreshToken(), "refresh_token");
        if (refreshed == null || refreshed.accessToken() == null || refreshed.accessToken().isBlank()) {
            throw new IllegalStateException("Google did not return an access token");
        }
        String refreshToken = refreshed.refreshToken() == null || refreshed.refreshToken().isBlank()
                ? request.refreshToken()
                : refreshed.refreshToken();
        LOG.infof("Refreshed Google access token workAccountId=%s expiresInSeconds=%d rotatedRefreshToken=%s",
                request.workAccountId(), refreshed.expiresIn(),
                !Objects.equals(refreshToken, request.refreshToken()));
        return new TokenState(
                refreshed.accessToken(),
                now.plusSeconds(refreshed.expiresIn()),
                refreshToken);
    }

    private EmailMessage toMessage(GmailMailboxClient.GmailMessage message) {
        MessageContent content = content(message.payload());
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
                content.html());
    }

    private static MessageContent content(GmailMailboxClient.MessagePayload payload) {
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder();
        collectContent(payload, text, html);
        String plainText = text.isEmpty() ? null : text.toString();
        String renderedHtml = html.isEmpty() ? textAsHtml(plainText) : html.toString();
        return new MessageContent(
                plainText,
                renderedHtml);
    }

    private static void collectContent(
            GmailMailboxClient.MessagePayload payload,
            StringBuilder text,
            StringBuilder html) {
        if (payload == null) return;
        if (payload.body() != null && payload.body().data() != null) {
            String decoded = decode(payload.body().data());
            if ("text/plain".equalsIgnoreCase(payload.mimeType())) append(text, decoded);
            if ("text/html".equalsIgnoreCase(payload.mimeType())) append(html, decoded);
        }
        if (payload.parts() != null) {
            payload.parts().forEach(part -> collectContent(part, text, html));
        }
    }

    private static String decode(String data) {
        try {
            return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidData) {
            return null;
        }
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
