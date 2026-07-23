package com.casiq.workaccount.core.polling;

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP;

@ApplicationScoped
public class EmailPollingScheduler {
    private static final Logger LOG = Logger.getLogger(EmailPollingScheduler.class);
    private static final int WORKER_THREADS = 10;
    @Inject EmailPollingStateService state;
    @Inject EmailPollingWorker worker;
    @ConfigProperty(name = "casiq.email-polling.enabled") boolean enabled;
    private final String instanceId = UUID.randomUUID().toString();
    private ExecutorService workers;

    @PostConstruct
    void initialize() {
        AtomicInteger sequence = new AtomicInteger();
        workers = Executors.newFixedThreadPool(WORKER_THREADS, task -> {
            Thread thread = new Thread(task, "email-polling-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        LOG.infof("Email polling scheduler initialized instance=%s workers=%d enabled=%s",
                instanceId, WORKER_THREADS, enabled);
    }

    @Scheduled(every = "${casiq.email-polling.scheduler-every}", concurrentExecution = SKIP)
    void schedule() {
        if (!enabled) {
            LOG.debug("Email polling scheduler tick skipped because it is disabled");
            return;
        }
        var claimed = state.claimDue(instanceId, Instant.now());
        LOG.debugf("Email polling scheduler instance=%s claimed=%d", instanceId, claimed.size());
        if (!claimed.isEmpty()) {
            LOG.infof("Submitting %d due email polling configuration(s) instance=%s",
                    claimed.size(), instanceId);
        }
        claimed.forEach(configId -> workers.submit(() -> worker.process(configId, instanceId)));
    }

    @PreDestroy
    void shutdown() {
        LOG.infof("Stopping email polling scheduler instance=%s", instanceId);
        workers.shutdown();
    }
}
