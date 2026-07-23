package com.casiq.workaccount.core.polling;

import com.casiq.workaccount.core.persistence.EmailPollingConfigEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class EmailPollingStateService {
    private static final Logger LOG = Logger.getLogger(EmailPollingStateService.class);
    @ConfigProperty(name = "casiq.email-polling.batch-size") int batchSize;
    @ConfigProperty(name = "casiq.email-polling.lock-seconds") long lockSeconds;
    @ConfigProperty(name = "casiq.email-polling.retry-delay-seconds") long retryDelaySeconds;

    @Transactional
    public List<UUID> claimDue(String owner, Instant now) {
        int limit = Math.max(1, Math.min(batchSize, 1000));
        @SuppressWarnings("unchecked")
        List<String> rawIds = Panache.getEntityManager().createNativeQuery("""
                SELECT CAST(config.id AS VARCHAR)
                FROM email_polling_config config
                JOIN work_account account ON account.id = config.work_account_id
                WHERE config.next_refresh_at IS NOT NULL
                  AND config.next_refresh_at <= ?1
                  AND (config.access_token IS NOT NULL OR account.refresh_token IS NOT NULL)
                  AND (config.locked_until IS NULL OR config.locked_until <= ?1)
                ORDER BY config.next_refresh_at
                LIMIT %d
                FOR UPDATE SKIP LOCKED
                """.formatted(limit))
                .setParameter(1, now)
                .getResultList();
        List<UUID> ids = rawIds.stream().map(UUID::fromString).toList();
        Instant lockedUntil = now.plusSeconds(lockSeconds);
        ids.forEach(id -> {
            EmailPollingConfigEntity config = EmailPollingConfigEntity.findById(id);
            config.lockOwner = owner;
            config.lockedUntil = lockedUntil;
            config.lastError = null;
            config.updatedAt = now;
        });
        LOG.debugf("Claimed %d email polling configuration(s) owner=%s lockUntil=%s",
                ids.size(), owner, lockedUntil);
        return List.copyOf(ids);
    }

    @Transactional
    public void fail(UUID configId, String owner, Throwable failure) {
        EmailPollingConfigEntity config = EmailPollingConfigEntity.findById(configId);
        if (config == null || !owner.equals(config.lockOwner)) return;
        Instant now = Instant.now();
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        config.lastError = message.substring(0, Math.min(message.length(), 1000));
        config.consecutiveFailures++;
        config.nextRefreshAt = now.plusSeconds(retryDelaySeconds);
        config.lockOwner = null;
        config.lockedUntil = null;
        config.updatedAt = now;
        LOG.warnf("Email polling scheduled for retry configId=%s owner=%s failures=%d retryAt=%s error=%s",
                configId, owner, config.consecutiveFailures, config.nextRefreshAt, config.lastError);
    }
}
