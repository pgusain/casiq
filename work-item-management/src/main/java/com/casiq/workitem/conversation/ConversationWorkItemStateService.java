package com.casiq.workitem.conversation;

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class ConversationWorkItemStateService {
    private static final Logger LOG = Logger.getLogger(ConversationWorkItemStateService.class);
    @ConfigProperty(name = "casiq.conversation-work-item.batch-size") int batchSize;
    @ConfigProperty(name = "casiq.conversation-work-item.lock-seconds") long lockSeconds;
    @ConfigProperty(name = "casiq.conversation-work-item.retry-delay-seconds") long retryDelaySeconds;

    @Transactional
    public List<Long> claimDue(String owner, Instant now) {
        MDC.put("tenantCode", owner == null ? "scheduler" : owner);
        try {
            int limit = Math.max(1, Math.min(batchSize, 1000));
            @SuppressWarnings("unchecked")
            List<String> rawIds = Panache.getEntityManager().createNativeQuery("""
                    SELECT CAST(conversation.id AS VARCHAR)
                    FROM work_account_conversation conversation
                    WHERE conversation.direction = 'INBOUND'
                      AND conversation.work_item_processed_at IS NULL
                      AND conversation.work_item_next_attempt_at <= ?1
                      AND (
                          conversation.work_item_locked_until IS NULL
                          OR conversation.work_item_locked_until <= ?1
                      )
                    ORDER BY conversation.received_at, conversation.id
                    LIMIT %d
                    FOR UPDATE SKIP LOCKED
                    """.formatted(limit))
                    .setParameter(1, now)
                    .getResultList();
            List<Long> ids = rawIds.stream().map(Long::valueOf).toList();
            Instant lockedUntil = now.plusSeconds(lockSeconds);
            ids.forEach(id -> Panache.getEntityManager().createNativeQuery("""
                            UPDATE work_account_conversation
                            SET work_item_lock_owner = ?1,
                                work_item_locked_until = ?2,
                                work_item_last_error = NULL
                            WHERE id = ?3
                            """)
                    .setParameter(1, owner)
                    .setParameter(2, lockedUntil)
                    .setParameter(3, id)
                    .executeUpdate());
            LOG.debugf("Claimed %d conversation(s) for work-item creation owner=%s lockUntil=%s",
                    ids.size(), owner, lockedUntil);
            return List.copyOf(ids);
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public void fail(Long conversationId, String owner, Throwable failure) {
        MDC.put("tenantCode", conversationId == null ? "unknown" : String.valueOf(conversationId));
        try {
            Instant now = Instant.now();
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            String truncated = message.substring(0, Math.min(message.length(), 1000));
            int updated = Panache.getEntityManager().createNativeQuery("""
                            UPDATE work_account_conversation
                            SET work_item_last_error = ?1,
                                work_item_failures = work_item_failures + 1,
                                work_item_next_attempt_at = ?2,
                                work_item_lock_owner = NULL,
                                work_item_locked_until = NULL
                            WHERE id = ?3
                              AND work_item_lock_owner = ?4
                              AND work_item_processed_at IS NULL
                            """)
                    .setParameter(1, truncated)
                    .setParameter(2, now.plusSeconds(retryDelaySeconds))
                    .setParameter(3, conversationId)
                    .setParameter(4, owner)
                    .executeUpdate();
            if (updated > 0) {
                LOG.warnf("Conversation work-item creation scheduled for retry conversationId=%s owner=%s retryAt=%s error=%s",
                        conversationId, owner, now.plusSeconds(retryDelaySeconds), truncated);
            } else {
                LOG.debugf("Conversation retry update skipped because lease is no longer owned conversationId=%s owner=%s",
                        conversationId, owner);
            }
        } finally {
            MDC.remove("tenantCode");
        }
    }
}
