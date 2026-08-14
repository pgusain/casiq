package com.casiq.workitem.conversation;

import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.workitem.persistence.WorkItemDefinitionEntity;
import com.casiq.workitem.persistence.WorkItemExecutionEntity;
import com.casiq.workitem.persistence.WorkItemDocumentEntity;
import com.casiq.workitem.persistence.WorkItemCommunicationEntity;
import com.casiq.workitem.persistence.WorkItemStatusEntity;
import com.casiq.workitem.persistence.DocumentOrigin;
import com.casiq.workitem.service.WorkItemDefinitionService;
import com.casiq.workitem.service.WorkItemNumberService;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@ApplicationScoped
public class ConversationWorkItemProcessor {
    private static final Logger LOG = Logger.getLogger(ConversationWorkItemProcessor.class);
    @Inject WorkItemNumberService workItemNumbers;
    @Inject Instance<ConversationWorkItemMatcher> conversationMatchers;
    @ConfigProperty(name = "casiq.work-item.email-cache-seconds", defaultValue = "300")
    long emailCacheSeconds;

    @Transactional
    public void createExecution(Long conversationId, String owner) {
        LOG.debugf("Creating work-item execution conversationId=%s owner=%s",
                conversationId, owner);
        Instant now = Instant.now();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = Panache.getEntityManager().createNativeQuery("""
                SELECT
                    CAST(conversation.tenant_id AS VARCHAR),
                    CAST(conversation.work_account_id AS VARCHAR),
                    account.email_id,
                    CAST(account.work_item_definition_id AS VARCHAR),
                    conversation.subject,
                    conversation.sender,
                    conversation.recipients,
                    conversation.sent_at,
                    conversation.content_html,
                    conversation.content_text,
                    conversation.snippet,
                    conversation.provider_thread_id,
                    provider.code,
                    conversation.provider_message_id,
                    conversation.rfc_message_id,
                    conversation.in_reply_to,
                    conversation.reference_ids
                FROM work_account_conversation conversation
                JOIN work_account account ON account.id = conversation.work_account_id
                JOIN email_provider_reference provider ON provider.id = conversation.provider_id
                WHERE conversation.id = ?1
                  AND conversation.direction = 'INBOUND'
                  AND conversation.work_item_processed_at IS NULL
                  AND conversation.work_item_lock_owner = ?2
                  AND conversation.work_item_locked_until > ?3
                FOR UPDATE
                """)
                .setParameter(1, conversationId)
                .setParameter(2, owner)
                .setParameter(3, now)
                .getResultList();
        if (rows.isEmpty()) {
            throw new IllegalStateException("Conversation work-item lease is missing or expired");
        }

        Object[] source = rows.get(0);
        Long tenantId = longValue(source[0]);
        Long workAccountId = longValue(source[1]);
        String workAccountEmail = String.valueOf(source[2]);
        Long definitionId = longValue(source[3]);
        String providerThreadId = nullableString(source[11]);
        String providerCode = String.valueOf(source[12]);

        // Serialize execution creation for one mailbox. Without this lock, two
        // simultaneously claimed messages from a new provider thread could both
        // observe no existing execution and create duplicate work items.
        Panache.getEntityManager().createNativeQuery("""
                        SELECT id
                        FROM work_account
                        WHERE id = ?1
                        FOR UPDATE
                        """)
                .setParameter(1, workAccountId)
                .getSingleResult();

        WorkItemExecutionEntity existing =
                WorkItemExecutionEntity.find("conversationId", conversationId).firstResult();
        if (existing == null) {
            ConversationWorkItemMatcher matcher = conversationMatchers.stream()
                    .filter(candidate -> providerCode.equals(candidate.providerCode()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No conversation matcher is configured for " + providerCode));
            Optional<Long> matchingExecution = matcher.matchingExecution(
                    new ConversationWorkItemMatcher.InboundConversation(
                            String.valueOf(source[13]),
                            providerThreadId,
                            nullableString(source[14]),
                            nullableString(source[15]),
                            nullableString(source[16])),
                    new CommunicationLookup(workAccountId, providerCode));
            if (matchingExecution.isPresent()) {
                existing = WorkItemExecutionEntity.findById(matchingExecution.get());
            }
        }
        Long executionId;
        if (existing == null) {
            TenantEntity tenant = TenantEntity.findById(tenantId);
            WorkItemDefinitionEntity definition = WorkItemDefinitionEntity.findById(definitionId);
            if (tenant == null) throw new IllegalStateException("Conversation tenant no longer exists");
            if (definition == null) throw new IllegalStateException("Linked work-item definition no longer exists");
            WorkItemStatusEntity initial = WorkItemStatusEntity.find(
                    "definition.id = ?1 and initialStatus = true", definitionId).firstResult();
            if (initial == null) {
                throw new IllegalStateException("Linked work-item definition has no initial status");
            }

            WorkItemExecutionEntity execution = new WorkItemExecutionEntity();
            execution.tenant = tenant;
            execution.workItemNumber = workItemNumbers.next(tenant);
            execution.workAccountId = workAccountId;
            execution.workAccountEmail = workAccountEmail;
            execution.workAccountNormalizedEmail =
                    workAccountEmail.trim().toLowerCase(Locale.ROOT);
            execution.conversationId = conversationId;
            execution.emailSubject = nullableString(source[4]);
            execution.emailSender = nullableString(source[5]);
            execution.emailRecipients = nullableString(source[6]);
            execution.emailSentAt = instant(source[7]);
            execution.emailContentHtml = null;
            execution.definition = definition;
            execution.currentStatus = initial;
            execution.createdAt = now;
            execution.archiveNextAttemptAt = now;
            execution.updatedAt = now;
            Panache.getEntityManager().persist(execution);
            Panache.getEntityManager().flush();
            executionId = execution.id;
            WorkItemCommunicationEntity communication =
                    createCommunication(execution, source, now);
            execution.initialCommunicationId = communication.id;
            copyAttachments(execution, communication, conversationId, now);
            LOG.infof("Created work-item execution executionId=%s conversationId=%s workAccountId=%s definitionId=%s initialStatus=%s",
                    executionId, conversationId, workAccountId, definitionId, initial.code);
        } else {
            existing = WorkItemExecutionEntity.find("id", existing.id)
                    .withLock(LockModeType.PESSIMISTIC_WRITE)
                    .firstResult();
            if (existing == null) {
                throw new IllegalStateException(
                        "Existing work-item execution no longer exists");
            }
            executionId = existing.id;
            existing.updatedAt = now;
            WorkItemCommunicationEntity communication =
                    createCommunication(existing, source, now);
            copyAttachments(existing, communication, conversationId, now);
            moveCustomerResponseToReady(existing, now);
            LOG.infof("Linked conversation to existing work item executionId=%s conversationId=%s providerThreadId=%s",
                    executionId, conversationId, providerThreadId);
        }

        var entityManager = Panache.getEntityManager();
        entityManager.flush();
        entityManager.createNativeQuery("""
                        UPDATE work_item_execution
                        SET conversation_id = NULL
                        WHERE conversation_id = ?1
                        """)
                .setParameter(1, conversationId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        UPDATE work_item_document
                        SET source_conversation_id = NULL,
                            source_attachment_id = NULL
                        WHERE source_conversation_id = ?1
                        """)
                .setParameter(1, conversationId)
                .executeUpdate();
        entityManager.clear();
        int deleted = entityManager.createNativeQuery("""
                        DELETE FROM work_account_conversation
                        WHERE id = ?1
                          AND work_item_lock_owner = ?2
                        """)
                .setParameter(1, conversationId)
                .setParameter(2, owner)
                .executeUpdate();
        if (deleted != 1) {
            throw new IllegalStateException(
                    "Conversation work-item lease was lost before source cleanup");
        }
        LOG.infof("Consumed conversation removed conversationId=%s executionId=%s owner=%s",
                conversationId, executionId, owner);
    }

    private void moveCustomerResponseToReady(
            WorkItemExecutionEntity execution,
            Instant changedAt) {
        if (!WorkItemDefinitionService.AWAITING_CUSTOMER_RESPONSE.equals(
                execution.currentStatus.code)) {
            return;
        }
        WorkItemStatusEntity readyToPick = WorkItemStatusEntity.find(
                "definition.id = ?1 and code = ?2",
                execution.definition.id,
                WorkItemDefinitionService.READY_TO_PICK).firstResult();
        if (readyToPick == null) {
            throw new IllegalStateException(
                    "Linked work-item definition has no READY_TO_PICK status");
        }
        execution.currentStatus = readyToPick;
        execution.updatedAt = changedAt;
        LOG.infof(
                "Moved work item to READY_TO_PICK after inbound customer response executionId=%s",
                execution.id);
    }

    private void copyAttachments(
            WorkItemExecutionEntity execution,
            WorkItemCommunicationEntity communication,
            Long conversationId,
            Instant createdAt) {
        @SuppressWarnings("unchecked")
        List<Object[]> attachments = Panache.getEntityManager().createNativeQuery("""
                SELECT CAST(id AS VARCHAR), filename, content_type, content_size,
                       content_data, storage_provider, storage_key
                FROM work_account_conversation_attachment
                WHERE conversation_id = ?1
                ORDER BY created_at, filename
                """)
                .setParameter(1, conversationId)
                .getResultList();
        for (Object[] source : attachments) {
            Long sourceId = longValue(source[0]);
            WorkItemDocumentEntity document = new WorkItemDocumentEntity();
            document.tenant = execution.tenant;
            document.execution = execution;
            document.sourceAttachmentId = sourceId;
            document.filename = String.valueOf(source[1]);
            document.contentType = nullableString(source[2]);
            document.contentSize = ((Number) source[3]).longValue();
            document.contentData = (byte[]) source[4];
            document.storageProvider = nullableString(source[5]);
            document.storageKey = nullableString(source[6]);
            document.origin = DocumentOrigin.INBOUND;
            document.sourceConversationId = conversationId;
            document.communication = communication;
            document.createdAt = createdAt;
            Panache.getEntityManager().persist(document);
        }
        LOG.debugf("Copied email attachments to work item executionId=%s count=%d",
                (Object) execution.id, attachments.size());
    }

    private WorkItemCommunicationEntity createCommunication(
            WorkItemExecutionEntity execution,
            Object[] source,
            Instant now) {
        String providerMessageId = String.valueOf(source[13]);
        WorkItemCommunicationEntity existing = WorkItemCommunicationEntity.find(
                "execution = ?1 and providerMessageId = ?2", execution, providerMessageId)
                .firstResult();
        if (existing != null) return existing;
        WorkItemCommunicationEntity communication = new WorkItemCommunicationEntity();
        communication.tenant = execution.tenant;
        communication.execution = execution;
        communication.workAccountId = execution.workAccountId;
        communication.providerCode = String.valueOf(source[12]);
        communication.providerMessageId = providerMessageId;
        communication.providerThreadId = nullableString(source[11]);
        communication.rfcMessageId = nullableString(source[14]);
        communication.inReplyTo = nullableString(source[15]);
        communication.referenceIds = nullableString(source[16]);
        communication.direction = "INBOUND";
        communication.subject = nullableString(source[4]);
        communication.sender = nullableString(source[5]);
        communication.recipients = nullableString(source[6]);
        communication.sentAt = instant(source[7]);
        communication.cachedContentHtml = nullableString(source[8]);
        communication.cachedContentText = nullableString(source[9]);
        communication.cachedSnippet = nullableString(source[10]);
        communication.cacheRefreshedAt = now;
        communication.cacheExpiresAt = now.plusSeconds(Math.max(0, emailCacheSeconds));
        communication.createdAt = now;
        Panache.getEntityManager().persist(communication);
        Panache.getEntityManager().flush();
        return communication;
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.valueOf(String.valueOf(value));
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        return Instant.parse(String.valueOf(value));
    }

    private static final class CommunicationLookup
            implements ConversationWorkItemMatcher.ExistingConversationLookup {
        private final Long workAccountId;
        private final String providerCode;

        private CommunicationLookup(Long workAccountId, String providerCode) {
            this.workAccountId = workAccountId;
            this.providerCode = providerCode;
        }

        @Override
        public Optional<Long> byProviderThreadId(String providerThreadId) {
            if (providerThreadId == null || providerThreadId.isBlank()) return Optional.empty();
            WorkItemCommunicationEntity communication = WorkItemCommunicationEntity.find(
                    "workAccountId = ?1 and providerCode = ?2 and providerThreadId = ?3 order by createdAt desc",
                    workAccountId, providerCode, providerThreadId).firstResult();
            return executionId(communication);
        }

        @Override
        public Optional<Long> byRfcMessageId(String rfcMessageId) {
            if (rfcMessageId == null || rfcMessageId.isBlank()) return Optional.empty();
            WorkItemCommunicationEntity communication = WorkItemCommunicationEntity.find(
                    "workAccountId = ?1 and providerCode = ?2 and rfcMessageId = ?3 order by createdAt desc",
                    workAccountId, providerCode, rfcMessageId).firstResult();
            return executionId(communication);
        }

        private static Optional<Long> executionId(WorkItemCommunicationEntity communication) {
            return communication == null || communication.execution == null
                    ? Optional.empty()
                    : Optional.of(communication.execution.id);
        }
    }
}
