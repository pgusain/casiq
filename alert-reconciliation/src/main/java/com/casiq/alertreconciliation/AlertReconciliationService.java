package com.casiq.alertreconciliation;

import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.workaccount.core.persistence.EmailPollingConfigEntity;
import com.casiq.workaccount.core.persistence.WorkAccountConversationEntity;
import com.casiq.workaccount.core.persistence.WorkAccountEntity;
import com.casiq.workaccount.gmail.GmailMailboxClient;
import com.casiq.workaccount.gmail.GoogleOAuthClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class AlertReconciliationService {
    private static final Logger LOG = Logger.getLogger(AlertReconciliationService.class);

    @Inject
    @RestClient
    GmailMailboxClient gmailMailboxClient;

    @Inject
    @RestClient
    GoogleOAuthClient googleOAuthClient;

    @ConfigProperty(name = "casiq.google.client-id", defaultValue = "")
    String googleClientId;

    @ConfigProperty(name = "casiq.google.client-secret", defaultValue = "")
    String googleClientSecret;

    @ConfigProperty(name = "casiq.alert-reconciliation.support-email", defaultValue = "solutions.techvisio@gmail.com")
    String supportEmail;

    @Transactional
    public void runDailyReconciliation() {
        LOG.info("Starting daily alert reconciliation");

        WorkAccountEntity supportAccount = resolveSupportAccount();
        if (supportAccount == null || supportAccount.refreshToken == null || supportAccount.refreshToken.isBlank()) {
            String message = "The support work account is not configured or has no refresh token.";
            LOG.error(message);
            notifySupport("Alert reconciliation configuration error", message, null);
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append("Daily alert reconciliation report\n");
        report.append("Generated at: ").append(Instant.now()).append("\n\n");
        report.append("Support sender: ").append(supportEmail).append("\n");

        long totalCapturedConversations = 0;
        long totalMailboxMessages = 0;
        int tenantCount = 0;
        int workAccountCount = 0;

        List<TenantEntity> tenants = TenantEntity.list("active = ?1 order by id", true);
        if (tenants.isEmpty()) {
            report.append("No active tenants were found.\n");
            sendSupportEmail("Daily alert reconciliation report", report.toString());
            LOG.info("Daily alert reconciliation completed with no active tenants");
            return;
        }

        for (TenantEntity tenant : tenants) {
            tenantCount++;
            List<WorkAccountEntity> workAccounts = WorkAccountEntity.find("tenant.id = ?1 order by normalizedEmailId", tenant.id).list();
            if (workAccounts.isEmpty()) {
                report.append("Tenant: ").append(tenant.normalizedCompanyCode)
                        .append(" | no work accounts configured\n");
                continue;
            }

            for (WorkAccountEntity workAccount : workAccounts.stream()
                    .sorted(Comparator.comparing(account -> account.emailId, String.CASE_INSENSITIVE_ORDER))
                    .toList()) {
                workAccountCount++;
                try {
                    long capturedConversations = WorkAccountConversationEntity.count(
                            "tenant.id = ?1 and workAccount.id = ?2",
                            tenant.id,
                            workAccount.id);
                    long mailboxMessages = countMailboxMessages(workAccount);
                    totalCapturedConversations += capturedConversations;
                    totalMailboxMessages += mailboxMessages;

                    report.append("Tenant: ")
                            .append(tenant.normalizedCompanyCode)
                            .append(" | Work account: ")
                            .append(workAccount.emailId)
                            .append(" | Provider: ")
                            .append(workAccount.provider == null ? "unknown" : workAccount.provider.code)
                            .append(" | Conversations captured: ")
                            .append(capturedConversations)
                            .append(" | Mailbox count: ")
                            .append(mailboxMessages)
                            .append("\n");
                } catch (Exception ex) {
                    LOG.errorf(ex,
                            "Failed to reconcile tenant=%s workAccount=%s",
                            tenant.normalizedCompanyCode,
                            workAccount.emailId);
                    report.append("Tenant: ")
                            .append(tenant.normalizedCompanyCode)
                            .append(" | Work account: ")
                            .append(workAccount.emailId)
                            .append(" | Error: ")
                            .append(ex.getMessage())
                            .append("\n");
                }
            }
        }

        report.append("\nSummary: activeTenants=")
                .append(tenantCount)
                .append(" | workAccounts=")
                .append(workAccountCount)
                .append(" | totalCapturedConversations=")
                .append(totalCapturedConversations)
                .append(" | totalMailboxMessages=")
                .append(totalMailboxMessages)
                .append("\n");

        sendSupportEmail("Daily alert reconciliation report", report.toString());
        LOG.infof("Daily alert reconciliation completed activeTenants=%d workAccounts=%d totalCapturedConversations=%d totalMailboxMessages=%d",
                tenantCount,
                workAccountCount,
                totalCapturedConversations,
                totalMailboxMessages);
    }

    @Transactional
    public void notifySupport(String subject, String body, Throwable error) {
        StringBuilder content = new StringBuilder();
        content.append(body);
        if (error != null) {
            content.append("\n\nStack trace:\n");
            for (StackTraceElement element : error.getStackTrace()) {
                content.append(element.toString()).append("\n");
            }
        }
        sendSupportEmail(subject, content.toString());
    }

    private long countMailboxMessages(WorkAccountEntity workAccount) {
        if (workAccount == null || workAccount.refreshToken == null || workAccount.refreshToken.isBlank()) {
            return 0;
        }
        if (googleClientId == null || googleClientId.isBlank() || googleClientSecret == null || googleClientSecret.isBlank()) {
            throw new IllegalStateException("Google OAuth client configuration is missing");
        }

        String accessToken = validAccessToken(workAccount);
        String authorization = "Bearer " + accessToken;
        GmailMailboxClient.MessageList page = gmailMailboxClient.messages(authorization, "in:anywhere", 1, null);
        if (page == null) {
            return 0;
        }
        if (page.resultSizeEstimate() != null) {
            return page.resultSizeEstimate();
        }
        if (page.messages() != null) {
            return page.messages().size();
        }
        return 0;
    }

    @Transactional
    private void sendSupportEmail(String subject, String body) {
        WorkAccountEntity supportAccount = resolveSupportAccount();
        if (supportAccount == null) {
            LOG.errorf("No support work account found for email=%s", supportEmail);
            return;
        }

        String rawEmail = buildRawEmailMessage(subject, body);
        String accessToken = validAccessToken(supportAccount);
        GmailMailboxClient.SendMessage payload = new GmailMailboxClient.SendMessage(
                Base64.getUrlEncoder().withoutPadding().encodeToString(rawEmail.getBytes(StandardCharsets.UTF_8)),
                null);

        try {
            gmailMailboxClient.send("Bearer " + accessToken, payload);
        } catch (Exception ex) {
            LOG.errorf(ex, "Unable to deliver alert reconciliation email from support account=%s", supportAccount.emailId);
        }
    }

    private WorkAccountEntity resolveSupportAccount() {
        String normalized = normalizeEmail(supportEmail);
        return WorkAccountEntity.find("tenant.id = ?1 and normalizedEmailId = ?2",
                1L,
                normalized).firstResult();
    }

    private String normalizeEmail(String emailId) {
        return emailId == null ? "" : emailId.trim().toLowerCase(Locale.ROOT);
    }

    private String validAccessToken(WorkAccountEntity workAccount) {
        if (workAccount == null || workAccount.refreshToken == null || workAccount.refreshToken.isBlank()) {
            throw new IllegalStateException("Support account has no refresh token");
        }
        GoogleOAuthClient.GoogleTokenResponse refreshed = googleOAuthClient.refresh(
                googleClientId,
                googleClientSecret,
                workAccount.refreshToken,
                "refresh_token");
        if (refreshed == null || refreshed.accessToken() == null || refreshed.accessToken().isBlank()) {
            throw new IllegalStateException("Google did not return an access token for support account=" + workAccount.emailId);
        }

        if (refreshed.refreshToken() != null && !refreshed.refreshToken().isBlank()) {
            workAccount.refreshToken = refreshed.refreshToken();
        }
        EmailPollingConfigEntity polling = EmailPollingConfigEntity.find("workAccount.id", workAccount.id).firstResult();
        if (polling != null) {
            polling.accessToken = refreshed.accessToken();
            polling.accessTokenExpiresAt = Instant.now().plusSeconds(refreshed.expiresIn());
            polling.nextRefreshAt = Instant.now();
            polling.updatedAt = Instant.now();
        }
        return refreshed.accessToken();
    }

    private String buildRawEmailMessage(String subject, String body) {
        StringBuilder rawMessage = new StringBuilder();
        rawMessage.append("From: <")
                .append(supportEmail)
                .append(">\r\n");
        rawMessage.append("To: <")
                .append(supportEmail)
                .append(">\r\n");
        rawMessage.append("Subject: ")
                .append(subject)
                .append("\r\n");
        rawMessage.append("MIME-Version: 1.0\r\n");
        rawMessage.append("Content-Type: text/plain; charset=UTF-8\r\n");
        rawMessage.append("\r\n");
        rawMessage.append(body)
                .append("\r\n");
        return rawMessage.toString();
    }
}
