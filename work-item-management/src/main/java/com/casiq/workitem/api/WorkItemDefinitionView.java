package com.casiq.workitem.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkItemDefinitionView(
        UUID id, UUID tenantId, String companyCode, String type, String displayName,
        boolean globalScope, UUID overridesDefinitionId, boolean active,
        List<StatusView> statuses, List<TransitionView> transitions,
        Instant createdAt, Instant updatedAt) {
    public record StatusView(UUID id, String code, String displayName, boolean initialStatus,
                             boolean terminalStatus, int sortOrder) {}
    public record TransitionView(UUID id, String fromStatus, String toStatus, String label) {}
}
