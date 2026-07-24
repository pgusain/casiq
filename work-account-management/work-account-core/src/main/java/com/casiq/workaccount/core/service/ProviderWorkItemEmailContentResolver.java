package com.casiq.workaccount.core.service;

import com.casiq.workaccount.core.persistence.EmailPollingConfigEntity;
import com.casiq.workaccount.core.persistence.WorkAccountEntity;
import com.casiq.workaccount.core.persistence.WorkAccountConversationEntity;
import com.casiq.workaccount.core.polling.EmailProviderConnector;
import com.casiq.workitem.service.WorkItemEmailContentResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ProviderWorkItemEmailContentResolver
        implements WorkItemEmailContentResolver {
    private static final Logger LOG =
            Logger.getLogger(ProviderWorkItemEmailContentResolver.class);

    @Inject Instance<EmailProviderConnector> connectors;

    @Override
    @Transactional
    public ResolvedContent resolve(EmailReference reference) {
        WorkAccountConversationEntity conversation =
                WorkAccountConversationEntity.find(
                        "tenant.id = ?1 and workAccount.id = ?2 and provider.code = ?3 and providerMessageId = ?4",
                        reference.tenantId(),
                        reference.workAccountId(),
                        reference.providerCode(),
                        reference.providerMessageId())
                        .firstResult();
        if (conversation != null
                && conversation.contentHtml != null
                && !conversation.contentHtml.isBlank()) {
            LOG.debugf(
                    "Resolved work-item email HTML from conversation table workAccountId=%s providerMessageId=%s",
                    reference.workAccountId(), reference.providerMessageId());
            return new ResolvedContent(
                    conversation.subject,
                    conversation.sender,
                    conversation.recipients,
                    conversation.sentAt,
                    conversation.snippet,
                    conversation.contentText,
                    conversation.contentHtml,
                    "CONVERSATION_TABLE");
        }

        EmailPollingConfigEntity config = EmailPollingConfigEntity.find(
                        "workAccount.id = ?1", reference.workAccountId())
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (config == null
                || !config.workAccount.tenant.id.equals(reference.tenantId())) {
            throw new IllegalStateException("Email polling configuration is unavailable");
        }
        WorkAccountEntity account = config.workAccount;
        EmailProviderConnector connector = connectors.stream()
                .filter(candidate ->
                        reference.providerCode().equals(candidate.providerCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No email connector is configured for "
                                + reference.providerCode()));
        EmailProviderConnector.ReadResult result = connector.read(
                new EmailProviderConnector.ReadRequest(
                        account.id,
                        config.emailId,
                        account.refreshToken,
                        config.accessToken,
                        config.accessTokenExpiresAt,
                        reference.providerMessageId()));
        if (result == null || result.message() == null) {
            throw new IllegalStateException("Email provider returned no message");
        }
        config.accessToken = result.accessToken();
        config.accessTokenExpiresAt = result.accessTokenExpiresAt();
        if (result.refreshToken() != null && !result.refreshToken().isBlank()) {
            account.refreshToken = result.refreshToken();
        }
        var message = result.message();
        if (conversation != null) {
            conversation.subject = message.subject();
            conversation.sender = message.sender();
            conversation.recipients = message.recipients();
            conversation.sentAt = message.sentAt();
            conversation.snippet = message.snippet();
            conversation.contentText = message.contentText();
            conversation.contentHtml = message.contentHtml();
            LOG.infof(
                    "Hydrated missing conversation HTML from email provider conversationId=%s workAccountId=%s providerMessageId=%s",
                    conversation.id, reference.workAccountId(),
                    reference.providerMessageId());
        } else {
            LOG.debugf(
                    "Resolved purged work-item email HTML from provider workAccountId=%s providerMessageId=%s",
                    reference.workAccountId(), reference.providerMessageId());
        }
        return new ResolvedContent(
                message.subject(),
                message.sender(),
                message.recipients(),
                message.sentAt(),
                message.snippet(),
                message.contentText(),
                message.contentHtml(),
                "PROVIDER");
    }
}
