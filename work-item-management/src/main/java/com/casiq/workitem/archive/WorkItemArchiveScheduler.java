package com.casiq.workitem.archive;

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
public class WorkItemArchiveScheduler {
    private static final Logger LOG =
            Logger.getLogger(WorkItemArchiveScheduler.class);
    @Inject WorkItemArchiveStateService state;
    @Inject WorkItemArchiveProcessor processor;
    @ConfigProperty(name = "casiq.work-item-archive.enabled")
    boolean enabled;
    @ConfigProperty(name = "casiq.work-item-archive.worker-threads")
    int workerThreads;
    private final String instanceId = UUID.randomUUID().toString();
    private ExecutorService workers;

    @PostConstruct
    void initialize() {
        int threads = Math.max(1, Math.min(workerThreads, 32));
        AtomicInteger sequence = new AtomicInteger();
        workers = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(
                    task,
                    "work-item-archive-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        LOG.infof(
                "Work-item archive scheduler initialized instance=%s workers=%d enabled=%s",
                instanceId, threads, enabled);
    }

    @Scheduled(
            cron = "${casiq.work-item-archive.scheduler-cron}",
            timeZone = "${casiq.work-item-archive.time-zone}",
            concurrentExecution = SKIP)
    void schedule() {
        if (!enabled) {
            LOG.debug(
                    "Work-item archive scheduler tick skipped because it is disabled");
            return;
        }
        var claimed = state.claimCompleted(instanceId, Instant.now());
        LOG.infof(
                "Submitting %d completed work item(s) for archival instance=%s",
                claimed.size(), instanceId);
        claimed.forEach(executionId ->
                workers.submit(() ->
                        processor.archive(executionId, instanceId)));
    }

    @PreDestroy
    void shutdown() {
        LOG.infof(
                "Stopping work-item archive scheduler instance=%s", instanceId);
        workers.shutdown();
    }
}
