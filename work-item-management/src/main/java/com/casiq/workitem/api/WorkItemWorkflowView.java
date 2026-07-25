package com.casiq.workitem.api;

import java.time.Instant;
import java.util.List;

public final class WorkItemWorkflowView {
    private WorkItemWorkflowView() {}

    public record Assignment(
            Long id, String assignmentType, Long tenantId, Long definitionId, String workItemType,
            Long statusId, String statusCode, Long transitionId, String transitionLabel,
            Long userId, String username, Instant createdAt) {}

    public record Transition(Long id, String label, String fromStatus, String toStatus) {}

    public record Activity(String transitionLabel, String fromStatus, String toStatus,
                           Long performedByUserId, String performedByUsername, Instant performedAt) {}

    public record Execution(
            Long id, long workItemNumber, Long workAccountId, Long conversationId,
            String emailId, String emailSubject, String emailSender,
            Long definitionId, String workItemType,
            String workItemDisplayName, Long currentStatusId, String currentStatus,
            String currentStatusDisplayName, boolean terminal,
            boolean dataMigrated,
            Long assignedUserId, String assignedUsername, boolean assignedToCurrentUser,
            List<Transition> allowedTransitions,
            List<Activity> activities, Instant updatedAt) {}

    public record PickResult(
            Execution execution,
            boolean newlyAssigned,
            boolean reassigned) {}

    public record Conversation(
            Long id,
            String subject,
            String sender,
            String recipients,
            Instant sentAt,
            String snippet,
            String contentText,
            String contentHtml,
            String direction,
            String contentSource,
            boolean staleFallback) {}

    public record Document(
            Long id,
            String filename,
            String contentType,
            long size,
            String origin,
            Long sourceConversationId,
            String uploadedByUsername,
            Instant createdAt) {}

    public record InternalNote(
            Long id,
            Long authorUserId,
            String authorUsername,
            String content,
            Instant createdAt) {}

    public record Detail(
            Execution execution,
            Conversation conversation,
            List<Conversation> communications,
            List<Document> documents,
            List<InternalNote> internalNotes,
            boolean readOnly) {}

    public record WorkPage(
            List<Execution> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            String sortBy,
            String sortDirection) {}

    public record StatusCount(
            String status,
            String displayName,
            boolean terminal,
            long count) {}
}
