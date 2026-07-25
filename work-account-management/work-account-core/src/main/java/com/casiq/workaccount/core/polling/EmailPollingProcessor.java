package com.casiq.workaccount.core.polling;

import com.casiq.storage.AttachmentStorage;
import com.casiq.workaccount.core.persistence.EmailPollingConfigEntity;
import com.casiq.workaccount.core.persistence.ConversationDirection;
import com.casiq.workaccount.core.persistence.WorkAccountConversationEntity;
import com.casiq.workaccount.core.persistence.WorkAccountConversationAttachmentEntity;
import com.casiq.workaccount.core.persistence.WorkAccountEntity;
import com.casiq.workitem.persistence.WorkItemCommunicationEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@ApplicationScoped
public class EmailPollingProcessor {
    private static final Logger LOG = Logger.getLogger(EmailPollingProcessor.class);
    @Inject Instance<EmailProviderConnector> connectors;
    @Inject AttachmentStorage attachmentStorage;
    @ConfigProperty(name = "casiq.email-polling.poll-interval-seconds") long pollIntervalSeconds;
    @ConfigProperty(name = "casiq.email-polling.initial-lookback-hours") long initialLookbackHours;

    @Transactional
    public void poll(Long configId, String owner) {
        LOG.debugf("Processing email polling configuration configId=%s owner=%s", configId, owner);
        EmailPollingConfigEntity config = EmailPollingConfigEntity.findById(configId);
        if (config == null || !owner.equals(config.lockOwner)) {
            LOG.debugf("Email polling configuration skipped because lease is not owned configId=%s owner=%s",
                    configId, owner);
            return;
        }

        Instant startedAt = Instant.now();
        if (config.lockedUntil == null || !config.lockedUntil.isAfter(startedAt)) {
            throw new IllegalStateException("Email polling lease expired before processing");
        }

        WorkAccountEntity account = config.workAccount;
        if (account == null) throw new IllegalStateException("Work account no longer exists");
        String providerCode = config.provider.code;
        EmailProviderConnector connector = connectors.stream()
                .filter(candidate -> providerCode.equals(candidate.providerCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No email polling connector is configured for " + providerCode));
        LOG.debugf("Dispatching email polling configId=%s workAccountId=%s provider=%s after=%s",
                configId, account.id, providerCode, config.lastPolledAt);

        Instant after = config.lastPolledAt == null
                ? startedAt.minus(initialLookbackHours, ChronoUnit.HOURS)
                : config.lastPolledAt;
        EmailProviderConnector.PollResult result = connector.fetch(
                new EmailProviderConnector.PollRequest(
                        account.id,
                        config.emailId,
                        account.refreshToken,
                        config.accessToken,
                        config.accessTokenExpiresAt,
                        after));
        if (result == null) {
            throw new IllegalStateException(providerCode + " connector returned no polling result");
        }

        int persisted = persistNewMessages(account, result.messages(), startedAt);
        config.accessToken = result.accessToken();
        config.accessTokenExpiresAt = result.accessTokenExpiresAt();
        if (result.refreshToken() != null && !result.refreshToken().isBlank()) {
            account.refreshToken = result.refreshToken();
        }
        config.lastPolledAt = startedAt;
        config.nextRefreshAt = startedAt.plusSeconds(pollIntervalSeconds);
        config.lockOwner = null;
        config.lockedUntil = null;
        config.lastError = null;
        config.consecutiveFailures = 0;
        config.updatedAt = Instant.now();
        LOG.infof("Email polling completed configId=%s workAccountId=%s provider=%s fetched=%d persisted=%d nextPollAt=%s",
                configId, account.id, providerCode,
                result.messages() == null ? 0 : result.messages().size(),
                persisted, config.nextRefreshAt);
    }

    private int persistNewMessages(
            WorkAccountEntity account,
            List<EmailProviderConnector.EmailMessage> messages,
            Instant receivedAt) {
        if (messages == null) return 0;
        int persisted = 0;
        for (EmailProviderConnector.EmailMessage message : messages) {
            if (message == null || message.providerMessageId() == null
                    || WorkAccountConversationEntity.count(
                    "workAccount.id = ?1 and providerMessageId = ?2",
                    account.id, message.providerMessageId()) > 0
                    || WorkItemCommunicationEntity.count(
                    "workAccountId = ?1 and providerMessageId = ?2",
                    account.id, message.providerMessageId()) > 0) {
                continue;
            }
            WorkAccountConversationEntity conversation = new WorkAccountConversationEntity();
            conversation.tenant = account.tenant;
            conversation.workAccount = account;
            conversation.provider = account.provider;
            conversation.providerMessageId = message.providerMessageId();
            conversation.providerThreadId = message.providerThreadId();
            conversation.rfcMessageId = message.rfcMessageId();
            conversation.inReplyTo = message.inReplyTo();
            conversation.referenceIds = message.referenceIds();
            conversation.direction = ConversationDirection.INBOUND;
            conversation.subject = message.subject();
            conversation.sender = message.sender();
            conversation.recipients = message.recipients();
            conversation.sentAt = message.sentAt();
            conversation.snippet = message.snippet();
            conversation.payloadJson = message.payloadJson();
            conversation.contentText = message.contentText();
            conversation.contentHtml = message.contentHtml();
            conversation.receivedAt = receivedAt;
            Panache.getEntityManager().persist(conversation);
            persistAttachments(conversation, message.attachments(), receivedAt);
            persisted++;
        }
        return persisted;
    }

    private void persistAttachments(
            WorkAccountConversationEntity conversation,
            List<EmailProviderConnector.EmailAttachment> attachments,
            Instant receivedAt) {
        if (attachments == null) return;
        for (EmailProviderConnector.EmailAttachment attachment : attachments) {
            if (attachment == null || attachment.providerAttachmentId() == null
                    || attachment.filename() == null || attachment.content() == null) {
                continue;
            }
            WorkAccountConversationAttachmentEntity entity =
                    new WorkAccountConversationAttachmentEntity();
            AttachmentStorage.StoredObject stored = attachmentStorage.put(
                    conversation.tenant.id,
                    "inbound",
                    attachment.filename(),
                    attachment.contentType(),
                    attachment.content());
            entity.tenant = conversation.tenant;
            entity.conversation = conversation;
            entity.providerAttachmentId = attachment.providerAttachmentId();
            entity.filename = attachment.filename();
            entity.contentType = attachment.contentType();
            entity.contentSize = stored.size();
            entity.storageProvider = stored.provider();
            entity.storageKey = stored.key();
            entity.contentData = null;
            entity.createdAt = receivedAt;
            Panache.getEntityManager().persist(entity);
        }
        LOG.debugf("Captured email attachments conversationId=%s count=%d",
                (Object) conversation.id, attachments.size());
    }
}
