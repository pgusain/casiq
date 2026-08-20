package com.casiq.workaccount.core.service;

import com.casiq.storage.AttachmentStorage;
import com.casiq.workaccount.core.persistence.*;
import com.casiq.workaccount.core.polling.EmailProviderConnector;
import com.casiq.workitem.service.WorkItemWorkflowService;
import com.casiq.workitem.persistence.WorkItemDocumentEntity;
import com.casiq.workitem.persistence.WorkItemExecutionEntity;
import com.casiq.workitem.persistence.WorkItemCommunicationEntity;
import com.casiq.workitem.persistence.DocumentOrigin;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class WorkAccountReplyService {
    private static final Logger LOG = Logger.getLogger(WorkAccountReplyService.class);

    @Inject WorkItemWorkflowService workflows;
    @Inject Instance<EmailProviderConnector> connectors;
    @Inject AttachmentStorage attachmentStorage;
    @ConfigProperty(name = "casiq.attachment-storage.max-bytes") long maxAttachmentBytes;
    @ConfigProperty(name = "casiq.work-item.email-cache-seconds", defaultValue = "300")
    long emailCacheSeconds;

    @Transactional
    public ReplyView reply(
            String token,
            Long executionId,
            UUID requestId,
            String htmlBody,
            List<Long> requestedDocumentIds) {
        if (requestId == null) throw new BadRequestException("requestId is required");
        String body = htmlBody == null ? "" : htmlBody.trim();
        if (body.isBlank()) throw new BadRequestException("Reply content is required");
        if (body.length() > 60_000) {
            throw new BadRequestException("Reply content must not exceed 60000 characters");
        }

        WorkItemWorkflowService.ReplyTarget target = workflows.replyTarget(token, executionId);
        MDC.put("tenantCode", String.valueOf(target.tenantId()));
        try {
            LOG.debugf("Sending outbound reply executionId=%s requestId=%s workAccountId=%s documentCount=%d",
                    executionId, requestId, target.workAccountId(), requestedDocumentIds == null ? 0 : requestedDocumentIds.size());
            WorkItemCommunicationEntity existingCommunication =
                    WorkItemCommunicationEntity.find(
                            "outboundRequestId", requestId).firstResult();
            if (existingCommunication != null) {
                assertSameCommunication(existingCommunication, target);
                LOG.infof("Reused idempotent email reply executionId=%s requestId=%s communicationId=%s",
                        executionId, requestId, existingCommunication.id);
                return view(existingCommunication);
            }
            WorkAccountConversationEntity existing = WorkAccountConversationEntity.find(
                    "outboundRequestId", requestId).firstResult();
            if (existing != null) {
                assertSameConversation(existing, target);
                LOG.infof("Reused idempotent email reply executionId=%s requestId=%s conversationId=%s",
                        executionId, requestId, existing.id);
                return view(existing);
            }

            EmailPollingConfigEntity config = EmailPollingConfigEntity.find(
                            "workAccount.id", target.workAccountId())
                    .withLock(LockModeType.PESSIMISTIC_WRITE)
                    .firstResult();
            if (config == null) throw new NotFoundException("Email polling configuration not found");

            // Recheck after taking the account lock so simultaneous retries cannot send twice.
            existingCommunication = WorkItemCommunicationEntity.find(
                    "outboundRequestId", requestId).firstResult();
            if (existingCommunication != null) {
                assertSameCommunication(existingCommunication, target);
                return view(existingCommunication);
            }

            WorkItemCommunicationEntity original = WorkItemCommunicationEntity.find(
                    "execution.id = ?1 and direction = 'INBOUND' order by sentAt desc, createdAt desc",
                    target.executionId()).firstResult();
            if (original == null || !original.tenant.id.equals(target.tenantId())) {
                throw new NotFoundException("Source email conversation not found");
            }
            WorkItemExecutionEntity execution =
                    WorkItemExecutionEntity.findById(target.executionId());
            List<SelectedDocument> selectedDocuments = selectedDocuments(
                    target, requestedDocumentIds);
            WorkAccountEntity account = config.workAccount;
            String providerCode = config.provider.code;
            EmailProviderConnector connector = connectors.stream()
                    .filter(candidate -> providerCode.equals(candidate.providerCode()))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException(
                            "No email connector is configured for " + providerCode));

            EmailProviderConnector.ReplyResult result = connector.reply(
                    new EmailProviderConnector.ReplyRequest(
                            account.id,
                            config.emailId,
                            account.refreshToken,
                            config.accessToken,
                            config.accessTokenExpiresAt,
                            original.sender,
                            original.subject,
                            original.providerMessageId,
                            original.providerThreadId,
                            original.rfcMessageId,
                            original.referenceIds,
                            body,
                            selectedDocuments.stream()
                                    .map(selected -> new EmailProviderConnector.OutgoingAttachment(
                                            selected.entity().id,
                                            selected.entity().filename,
                                            selected.entity().contentType,
                                            selected.content()))
                                    .toList()));
            if (result == null || result.message() == null
                    || result.message().providerMessageId() == null) {
                throw new IllegalStateException(providerCode + " connector returned no sent message");
            }

            EmailProviderConnector.EmailMessage sent = result.message();
            WorkAccountConversationEntity conversation = new WorkAccountConversationEntity();
            conversation.tenant = account.tenant;
            conversation.workAccount = account;
            conversation.provider = account.provider;
            conversation.providerMessageId = sent.providerMessageId();
            conversation.providerThreadId = sent.providerThreadId();
            conversation.rfcMessageId = sent.rfcMessageId();
            conversation.inReplyTo = sent.inReplyTo();
            conversation.referenceIds = sent.referenceIds();
            conversation.direction = ConversationDirection.OUTBOUND;
            conversation.subject = sent.subject();
            conversation.sender = sent.sender();
            conversation.recipients = sent.recipients();
            conversation.sentAt = sent.sentAt();
            conversation.snippet = sent.snippet();
            conversation.payloadJson = sent.payloadJson();
            conversation.contentText = sent.contentText();
            conversation.contentHtml = sent.contentHtml() == null ? body : sent.contentHtml();
            conversation.outboundRequestId = requestId;
            conversation.workItemExecution = execution;
            conversation.receivedAt = Instant.now();
            Panache.getEntityManager().persist(conversation);
            Panache.getEntityManager().flush();
            WorkItemCommunicationEntity communication = communication(
                    execution, conversation, sent, requestId, body);
            Panache.getEntityManager().persist(communication);
            captureOutboundDocuments(
                    execution, conversation, communication,
                    selectedDocuments, conversation.receivedAt);

            config.accessToken = result.accessToken();
            config.accessTokenExpiresAt = result.accessTokenExpiresAt();
            config.updatedAt = conversation.receivedAt;
            if (result.refreshToken() != null && !result.refreshToken().isBlank()) {
                account.refreshToken = result.refreshToken();
                account.updatedAt = conversation.receivedAt;
            }
            Panache.getEntityManager().flush();
            LOG.infof("Tracked outbound email reply executionId=%s requestId=%s conversationId=%s provider=%s providerMessageId=%s",
                    executionId, requestId, conversation.id, providerCode,
                    conversation.providerMessageId);
            return view(communication);
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error sending outbound reply executionId=%s requestId=%s workAccountId=%s",
                    executionId, requestId, target.workAccountId(), e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    private List<SelectedDocument> selectedDocuments(
            WorkItemWorkflowService.ReplyTarget target,
            List<Long> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) return List.of();
        LinkedHashSet<Long> ids = new LinkedHashSet<>(requestedIds);
        if (ids.size() > 20) {
            throw new BadRequestException("At most 20 documents can be attached to one reply");
        }
        List<SelectedDocument> selected = new ArrayList<>();
        long totalSize = 0;
        for (Long id : ids) {
            WorkItemDocumentEntity document = WorkItemDocumentEntity.find(
                    "id = ?1 and execution.id = ?2 and tenant.id = ?3",
                    id, target.executionId(), target.tenantId()).firstResult();
            if (document == null) {
                throw new BadRequestException("Selected document is not part of this work item");
            }
            byte[] content = document.contentData != null
                    ? document.contentData
                    : attachmentStorage.get(target.tenantId(), document.storageKey);
            totalSize += content.length;
            if (totalSize > maxAttachmentBytes) {
                throw new BadRequestException(
                        "Reply attachments exceed the configured maximum total size");
            }
            selected.add(new SelectedDocument(document, content));
        }
        return List.copyOf(selected);
    }

    private void captureOutboundDocuments(
            WorkItemExecutionEntity execution,
            WorkAccountConversationEntity conversation,
            WorkItemCommunicationEntity communication,
            List<SelectedDocument> selected,
            Instant createdAt) {
        for (SelectedDocument source : selected) {
            WorkItemDocumentEntity outbound = new WorkItemDocumentEntity();
            outbound.tenant = execution.tenant;
            outbound.execution = execution;
            outbound.filename = source.entity().filename;
            outbound.contentType = source.entity().contentType;
            outbound.contentSize = source.entity().contentSize;
            outbound.contentData = source.entity().contentData;
            outbound.storageProvider = source.entity().storageProvider;
            outbound.storageKey = source.entity().storageKey;
            outbound.origin = DocumentOrigin.OUTBOUND;
            outbound.sourceConversationId = conversation.id;
            outbound.communication = communication;
            outbound.createdAt = createdAt;
            Panache.getEntityManager().persist(outbound);
        }
    }

    private WorkItemCommunicationEntity communication(
            WorkItemExecutionEntity execution,
            WorkAccountConversationEntity conversation,
            EmailProviderConnector.EmailMessage sent,
            UUID requestId,
            String fallbackHtml) {
        WorkItemCommunicationEntity communication = new WorkItemCommunicationEntity();
        communication.tenant = execution.tenant;
        communication.execution = execution;
        communication.workAccountId = execution.workAccountId;
        communication.providerCode = conversation.provider.code;
        communication.providerMessageId = sent.providerMessageId();
        communication.providerThreadId = sent.providerThreadId();
        communication.rfcMessageId = sent.rfcMessageId();
        communication.inReplyTo = sent.inReplyTo();
        communication.referenceIds = sent.referenceIds();
        communication.direction = "OUTBOUND";
        communication.subject = sent.subject();
        communication.sender = sent.sender();
        communication.recipients = sent.recipients();
        communication.sentAt = sent.sentAt();
        communication.cachedSnippet = sent.snippet();
        communication.cachedContentText = sent.contentText();
        communication.cachedContentHtml =
                sent.contentHtml() == null ? fallbackHtml : sent.contentHtml();
        communication.cacheRefreshedAt = conversation.receivedAt;
        communication.cacheExpiresAt = conversation.receivedAt.plusSeconds(
                Math.max(0, emailCacheSeconds));
        communication.outboundRequestId = requestId;
        communication.createdAt = conversation.receivedAt;
        return communication;
    }

    private static void assertSameConversation(
            WorkAccountConversationEntity existing,
            WorkItemWorkflowService.ReplyTarget target) {
        if (!existing.tenant.id.equals(target.tenantId())
                || !existing.workAccount.id.equals(target.workAccountId())) {
            throw new BadRequestException("requestId was already used for another work item");
        }
    }

    private static void assertSameCommunication(
            WorkItemCommunicationEntity existing,
            WorkItemWorkflowService.ReplyTarget target) {
        if (!existing.tenant.id.equals(target.tenantId())
                || !existing.workAccountId.equals(target.workAccountId())) {
            throw new BadRequestException("requestId was already used for another work item");
        }
    }

    private static ReplyView view(WorkAccountConversationEntity conversation) {
        return new ReplyView(
                conversation.id,
                conversation.outboundRequestId,
                conversation.providerMessageId,
                conversation.providerThreadId,
                conversation.sentAt,
                conversation.recipients);
    }

    private static ReplyView view(WorkItemCommunicationEntity communication) {
        return new ReplyView(
                communication.id,
                communication.outboundRequestId,
                communication.providerMessageId,
                communication.providerThreadId,
                communication.sentAt,
                communication.recipients);
    }

    public record ReplyView(
            Long conversationId,
            UUID requestId,
            String providerMessageId,
            String providerThreadId,
            Instant sentAt,
            String recipients) {}

    private record SelectedDocument(
            WorkItemDocumentEntity entity,
            byte[] content) {}
}
