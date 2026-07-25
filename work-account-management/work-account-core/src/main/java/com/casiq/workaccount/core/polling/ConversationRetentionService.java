package com.casiq.workaccount.core.polling;

import com.casiq.workaccount.core.persistence.WorkAccountConversationEntity;
import com.casiq.workitem.persistence.WorkItemCommunicationEntity;
import com.casiq.workitem.persistence.WorkItemExecutionEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

import static jakarta.transaction.Transactional.TxType.REQUIRES_NEW;

@ApplicationScoped
public class ConversationRetentionService {
    private static final Logger LOG =
            Logger.getLogger(ConversationRetentionService.class);

    @Transactional(REQUIRES_NEW)
    public long purgeConversationBatch(Instant cutoff, int configuredBatchSize) {
        int limit = boundedBatchSize(configuredBatchSize);
        @SuppressWarnings("unchecked")
        List<Number> rows = Panache.getEntityManager().createNativeQuery("""
                        SELECT id
                        FROM work_account_conversation
                        WHERE received_at < ?1
                          AND (
                            work_item_processed_at IS NOT NULL
                            OR (
                                direction = 'OUTBOUND'
                                AND work_item_execution_id IS NOT NULL
                            )
                          )
                        ORDER BY received_at, id
                        LIMIT %d
                        FOR UPDATE SKIP LOCKED
                        """.formatted(limit))
                .setParameter(1, cutoff)
                .getResultList();
        List<Long> ids = rows.stream()
                .map(Number::longValue)
                .toList();
        if (ids.isEmpty()) {
            LOG.debugf(
                    "No materialized conversations are eligible for retention purge cutoff=%s",
                    cutoff);
            return 0;
        }

        WorkItemExecutionEntity.update(
                "conversationId = null where conversationId in ?1", ids);
        long deleted = WorkAccountConversationEntity.delete("id in ?1", ids);
        LOG.infof(
                "Purged %d materialized email conversation row(s) cutoff=%s",
                deleted, cutoff);
        return deleted;
    }

    @Transactional(REQUIRES_NEW)
    public long clearCommunicationCacheBatch(
            Instant cutoff,
            int configuredBatchSize) {
        int limit = boundedBatchSize(configuredBatchSize);
        @SuppressWarnings("unchecked")
        List<Number> rows = Panache.getEntityManager().createNativeQuery("""
                        SELECT id
                        FROM work_item_communication
                        WHERE cache_refreshed_at < ?1
                          AND (
                            cached_snippet IS NOT NULL
                            OR cached_content_text IS NOT NULL
                            OR cached_content_html IS NOT NULL
                          )
                        ORDER BY cache_refreshed_at, id
                        LIMIT %d
                        FOR UPDATE SKIP LOCKED
                        """.formatted(limit))
                .setParameter(1, cutoff)
                .getResultList();
        List<Long> ids = rows.stream()
                .map(Number::longValue)
                .toList();
        if (ids.isEmpty()) {
            LOG.debugf(
                    "No work-item communication cache is eligible for clearing cutoff=%s",
                    cutoff);
            return 0;
        }

        long cleared = WorkItemCommunicationEntity.update("""
                cachedSnippet = null,
                cachedContentText = null,
                cachedContentHtml = null,
                cacheExpiresAt = null
                where id in ?1
                """, ids);
        LOG.infof(
                "Cleared expired fallback content from %d work-item communication(s) cutoff=%s",
                cleared, cutoff);
        return cleared;
    }

    private static int boundedBatchSize(int configuredBatchSize) {
        return Math.max(1, Math.min(configuredBatchSize, 1000));
    }
}
