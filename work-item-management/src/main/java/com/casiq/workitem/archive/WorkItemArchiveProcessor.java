package com.casiq.workitem.archive;

import com.casiq.storage.AttachmentStorage;
import com.casiq.workitem.service.WorkItemWorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkItemArchiveProcessor {
    private static final Logger LOG =
            Logger.getLogger(WorkItemArchiveProcessor.class);
    @Inject WorkItemWorkflowService workflows;
    @Inject WorkItemArchiveCodec codec;
    @Inject WorkItemArchiveStateService state;

    public void archive(Long executionId, String owner) {
        LOG.debugf(
                "Work-item archive worker started executionId=%s owner=%s",
                executionId, owner);
        try {
            WorkItemArchivePayload payload =
                    workflows.archivePayload(executionId, owner);
            AttachmentStorage.StoredObject stored = codec.write(payload);
            state.complete(
                    executionId, owner, stored, payload.archivedAt());
        } catch (RuntimeException failure) {
            LOG.warnf(
                    failure,
                    "Work-item archive worker failed executionId=%s owner=%s",
                    executionId,
                    owner);
            state.fail(executionId, owner, failure);
        }
    }
}
