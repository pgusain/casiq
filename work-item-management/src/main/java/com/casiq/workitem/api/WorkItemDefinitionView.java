package com.casiq.workitem.api;

import java.time.Instant;
import java.util.List;
public record WorkItemDefinitionView(
        Long id, Long tenantId, String companyCode, String type, String displayName,
        boolean globalScope, Long overridesDefinitionId, boolean active,
        List<StatusView> statuses, List<TransitionView> transitions,
        Instant createdAt, Instant updatedAt) {
    public record StatusView(Long id, String code, String displayName, boolean initialStatus,
                             boolean terminalStatus, int sortOrder) {}
    public record TransitionView(Long id, String fromStatus, String toStatus, String label) {}
}
