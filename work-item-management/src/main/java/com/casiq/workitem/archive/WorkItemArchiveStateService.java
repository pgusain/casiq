package com.casiq.workitem.archive;

import com.casiq.storage.AttachmentStorage;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class WorkItemArchiveStateService {
    private static final Logger LOG =
            Logger.getLogger(WorkItemArchiveStateService.class);
    @ConfigProperty(name = "casiq.work-item-archive.batch-size")
    int batchSize;
    @ConfigProperty(name = "casiq.work-item-archive.lock-seconds")
    long lockSeconds;
    @ConfigProperty(name = "casiq.work-item-archive.retry-delay-seconds")
    long retryDelaySeconds;

    @Transactional
    public List<Long> claimCompleted(String owner, Instant now) {
        int limit = Math.max(1, Math.min(batchSize, 1000));
        @SuppressWarnings("unchecked")
        List<String> rawIds = Panache.getEntityManager().createNativeQuery("""
                SELECT CAST(execution.id AS VARCHAR)
                FROM work_item_execution execution
                JOIN work_item_status status
                  ON status.id = execution.current_status_id
                WHERE status.code = 'COMPLETED'
                  AND execution.data_migrated = FALSE
                  AND execution.archive_next_attempt_at <= ?1
                  AND (
                    execution.archive_locked_until IS NULL
                    OR execution.archive_locked_until <= ?1
                  )
                ORDER BY execution.updated_at, execution.id
                LIMIT %d
                FOR UPDATE SKIP LOCKED
                """.formatted(limit))
                .setParameter(1, now)
                .getResultList();
        List<Long> ids = rawIds.stream().map(Long::valueOf).toList();
        Instant lockedUntil = now.plusSeconds(lockSeconds);
        ids.forEach(id -> Panache.getEntityManager().createNativeQuery("""
                        UPDATE work_item_execution
                        SET archive_lock_owner = ?1,
                            archive_locked_until = ?2,
                            archive_last_error = NULL
                        WHERE id = ?3
                        """)
                .setParameter(1, owner)
                .setParameter(2, lockedUntil)
                .setParameter(3, id)
                .executeUpdate());
        LOG.infof(
                "Claimed %d completed work item(s) for archival owner=%s lockUntil=%s",
                ids.size(), owner, lockedUntil);
        return List.copyOf(ids);
    }

    @Transactional
    public void complete(
            Long executionId,
            String owner,
            AttachmentStorage.StoredObject stored,
            Instant archivedAt) {
        Panache.getEntityManager().createNativeQuery(
                        "DELETE FROM work_item_document WHERE execution_id = ?1")
                .setParameter(1, executionId)
                .executeUpdate();
        Panache.getEntityManager().createNativeQuery(
                        "DELETE FROM work_item_internal_note WHERE execution_id = ?1")
                .setParameter(1, executionId)
                .executeUpdate();
        Panache.getEntityManager().createNativeQuery(
                        "DELETE FROM work_item_activity WHERE execution_id = ?1")
                .setParameter(1, executionId)
                .executeUpdate();
        Panache.getEntityManager().createNativeQuery("""
                        UPDATE work_item_execution
                        SET initial_communication_id = NULL
                        WHERE id = ?1
                        """)
                .setParameter(1, executionId)
                .executeUpdate();
        Panache.getEntityManager().createNativeQuery(
                        "DELETE FROM work_item_communication WHERE execution_id = ?1")
                .setParameter(1, executionId)
                .executeUpdate();
        int updated = Panache.getEntityManager().createNativeQuery("""
                        UPDATE work_item_execution
                        SET data_migrated = TRUE,
                            archive_storage_provider = ?1,
                            archive_storage_key = ?2,
                            archived_at = ?3,
                            archive_lock_owner = NULL,
                            archive_locked_until = NULL,
                            archive_last_error = NULL,
                            archive_failures = 0,
                            version = version + 1
                        WHERE id = ?4
                          AND archive_lock_owner = ?5
                          AND data_migrated = FALSE
                        """)
                .setParameter(1, stored.provider())
                .setParameter(2, stored.key())
                .setParameter(3, archivedAt)
                .setParameter(4, executionId)
                .setParameter(5, owner)
                .executeUpdate();
        if (updated != 1) {
            throw new IllegalStateException(
                    "Work-item archive lease is missing or no longer owned");
        }
        LOG.infof(
                "Archived and purged work-item details executionId=%s owner=%s provider=%s key=%s size=%d",
                executionId, owner, stored.provider(), stored.key(), stored.size());
    }

    @Transactional
    public void fail(Long executionId, String owner, Throwable failure) {
        Instant now = Instant.now();
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        String truncated = message.substring(0, Math.min(message.length(), 1000));
        int updated = Panache.getEntityManager().createNativeQuery("""
                        UPDATE work_item_execution
                        SET archive_last_error = ?1,
                            archive_failures = archive_failures + 1,
                            archive_next_attempt_at = ?2,
                            archive_lock_owner = NULL,
                            archive_locked_until = NULL
                        WHERE id = ?3
                          AND archive_lock_owner = ?4
                          AND data_migrated = FALSE
                        """)
                .setParameter(1, truncated)
                .setParameter(2, now.plusSeconds(retryDelaySeconds))
                .setParameter(3, executionId)
                .setParameter(4, owner)
                .executeUpdate();
        if (updated > 0) {
            LOG.warnf(
                    "Work-item archival scheduled for retry executionId=%s owner=%s retryAt=%s error=%s",
                    executionId, owner,
                    now.plusSeconds(retryDelaySeconds), truncated);
        }
    }
}
