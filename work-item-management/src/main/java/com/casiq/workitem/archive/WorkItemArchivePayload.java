package com.casiq.workitem.archive;

import com.casiq.workitem.api.WorkItemWorkflowView;

import java.time.Instant;
import java.util.List;

public record WorkItemArchivePayload(
        int schemaVersion,
        Instant archivedAt,
        ArchivedExecution execution,
        WorkItemWorkflowView.Conversation conversation,
        List<WorkItemWorkflowView.Conversation> communications,
        List<ArchivedDocument> documents,
        List<WorkItemWorkflowView.InternalNote> internalNotes) {

    public record ArchivedExecution(
            Long id,
            long workItemNumber,
            Long tenantId,
            Long workAccountId,
            Long conversationId,
            String emailId,
            String emailSubject,
            String emailSender,
            String emailRecipients,
            Instant emailSentAt,
            Long definitionId,
            String workItemType,
            String workItemDisplayName,
            Long currentStatusId,
            String currentStatus,
            String currentStatusDisplayName,
            boolean terminal,
            Long assignedUserId,
            String assignedUsername,
            List<WorkItemWorkflowView.Activity> activities,
            Instant createdAt,
            Instant updatedAt) {}

    public record ArchivedDocument(
            WorkItemWorkflowView.Document metadata,
            byte[] content) {}
}
