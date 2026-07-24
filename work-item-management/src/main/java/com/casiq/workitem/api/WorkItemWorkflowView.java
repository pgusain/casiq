package com.casiq.workitem.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkItemWorkflowView {
    private WorkItemWorkflowView() {}

    public record Assignment(
            UUID id, String assignmentType, UUID tenantId, UUID definitionId, String workItemType,
            UUID statusId, String statusCode, UUID transitionId, String transitionLabel,
            UUID userId, String username, Instant createdAt) {}

    public record Transition(UUID id, String label, String fromStatus, String toStatus) {}

    public record Activity(String transitionLabel, String fromStatus, String toStatus,
                           UUID performedByUserId, String performedByUsername, Instant performedAt) {}

    public record Execution(
            UUID id, long workItemNumber, UUID workAccountId, UUID conversationId,
            String emailId, String emailSubject, String emailSender,
            UUID definitionId, String workItemType,
            String workItemDisplayName, UUID currentStatusId, String currentStatus,
            String currentStatusDisplayName, boolean terminal,
            List<Transition> allowedTransitions,
            List<Activity> activities, Instant updatedAt) {}

    public record Conversation(
            UUID id,
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
            UUID id,
            String filename,
            String contentType,
            long size,
            String origin,
            UUID sourceConversationId,
            String uploadedByUsername,
            Instant createdAt) {}

    public record InternalNote(
            UUID id,
            UUID authorUserId,
            String authorUsername,
            String content,
            Instant createdAt) {}

    public record Detail(
            Execution execution,
            Conversation conversation,
            List<Conversation> communications,
            List<Document> documents,
            List<InternalNote> internalNotes) {}

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
