package com.casiq.workitem.conversation;

import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.workitem.persistence.WorkItemDefinitionEntity;
import com.casiq.workitem.persistence.WorkItemExecutionEntity;
import com.casiq.workitem.persistence.WorkItemStatusEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class ConversationWorkItemProcessor {
    private static final Logger LOG = Logger.getLogger(ConversationWorkItemProcessor.class);

    @Transactional
    public void createExecution(UUID conversationId, String owner) {
        LOG.debugf("Creating work-item execution conversationId=%s owner=%s",
                conversationId, owner);
        Instant now = Instant.now();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = Panache.getEntityManager().createNativeQuery("""
                SELECT
                    CAST(conversation.tenant_id AS VARCHAR),
                    CAST(conversation.work_account_id AS VARCHAR),
                    account.email_id,
                    CAST(account.work_item_definition_id AS VARCHAR)
                FROM work_account_conversation conversation
                JOIN work_account account ON account.id = conversation.work_account_id
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
        UUID tenantId = UUID.fromString(String.valueOf(source[0]));
        UUID workAccountId = UUID.fromString(String.valueOf(source[1]));
        String workAccountEmail = String.valueOf(source[2]);
        UUID definitionId = UUID.fromString(String.valueOf(source[3]));

        WorkItemExecutionEntity existing =
                WorkItemExecutionEntity.find("conversationId", conversationId).firstResult();
        UUID executionId;
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
            execution.workAccountId = workAccountId;
            execution.workAccountEmail = workAccountEmail;
            execution.workAccountNormalizedEmail =
                    workAccountEmail.trim().toLowerCase(Locale.ROOT);
            execution.conversationId = conversationId;
            execution.definition = definition;
            execution.currentStatus = initial;
            execution.createdAt = now;
            execution.updatedAt = now;
            Panache.getEntityManager().persist(execution);
            Panache.getEntityManager().flush();
            executionId = execution.id;
            LOG.infof("Created work-item execution executionId=%s conversationId=%s workAccountId=%s definitionId=%s initialStatus=%s",
                    executionId, conversationId, workAccountId, definitionId, initial.code);
        } else {
            executionId = existing.id;
            LOG.debugf("Reusing existing work-item execution executionId=%s conversationId=%s",
                    executionId, conversationId);
        }

        Panache.getEntityManager().createNativeQuery("""
                        UPDATE work_account_conversation
                        SET work_item_processed_at = ?1,
                            work_item_lock_owner = NULL,
                            work_item_locked_until = NULL,
                            work_item_last_error = NULL,
                            work_item_failures = 0
                        WHERE id = ?2
                          AND work_item_lock_owner = ?3
                        """)
                .setParameter(1, now)
                .setParameter(2, conversationId)
                .setParameter(3, owner)
                .executeUpdate();
        LOG.infof("Conversation marked processed conversationId=%s executionId=%s owner=%s",
                conversationId, executionId, owner);
    }
}
