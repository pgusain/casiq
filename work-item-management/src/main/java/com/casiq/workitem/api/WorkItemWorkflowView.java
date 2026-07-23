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
            UUID id, UUID workAccountId, UUID conversationId, String emailId,
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
            String direction) {}

    public record Detail(Execution execution, Conversation conversation) {}

    public record WorkPage(
            List<Execution> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            String sortBy,
            String sortDirection) {}
}
