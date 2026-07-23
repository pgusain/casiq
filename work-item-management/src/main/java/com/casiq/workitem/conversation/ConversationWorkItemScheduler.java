package com.casiq.workitem.conversation;

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
public class ConversationWorkItemScheduler {
    private static final Logger LOG = Logger.getLogger(ConversationWorkItemScheduler.class);
    private static final int WORKER_THREADS = 10;
    @Inject ConversationWorkItemStateService state;
    @Inject ConversationWorkItemWorker worker;
    @ConfigProperty(name = "casiq.conversation-work-item.enabled") boolean enabled;
    private final String instanceId = UUID.randomUUID().toString();
    private ExecutorService workers;

    @PostConstruct
    void initialize() {
        AtomicInteger sequence = new AtomicInteger();
        workers = Executors.newFixedThreadPool(WORKER_THREADS, task -> {
            Thread thread = new Thread(
                    task,
                    "conversation-work-item-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        LOG.infof("Conversation work-item scheduler initialized instance=%s workers=%d enabled=%s",
                instanceId, WORKER_THREADS, enabled);
    }

    @Scheduled(
            every = "${casiq.conversation-work-item.scheduler-every}",
            concurrentExecution = SKIP)
    void schedule() {
        if (!enabled) {
            LOG.debug("Conversation work-item scheduler tick skipped because it is disabled");
            return;
        }
        var claimed = state.claimDue(instanceId, Instant.now());
        LOG.debugf("Conversation work-item scheduler instance=%s claimed=%d",
                instanceId, claimed.size());
        if (!claimed.isEmpty()) {
            LOG.infof("Submitting %d conversation(s) for work-item creation instance=%s",
                    claimed.size(), instanceId);
        }
        claimed.forEach(conversationId ->
                workers.submit(() -> worker.process(conversationId, instanceId)));
    }

    @PreDestroy
    void shutdown() {
        LOG.infof("Stopping conversation work-item scheduler instance=%s", instanceId);
        workers.shutdown();
    }
}
