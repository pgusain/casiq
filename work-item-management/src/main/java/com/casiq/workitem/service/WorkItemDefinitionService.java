package com.casiq.workitem.service;

import com.casiq.usermanagement.domain.UserRole;
import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.usermanagement.service.AuthService;
import com.casiq.workitem.api.WorkItemDefinitionView;
import com.casiq.workitem.persistence.WorkItemDefinitionEntity;
import com.casiq.workitem.persistence.WorkItemExecutionEntity;
import com.casiq.workitem.persistence.WorkItemStatusEntity;
import com.casiq.workitem.persistence.WorkItemTransitionEntity;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class WorkItemDefinitionService {
    public static final Long CASIQ_TENANT_ID = 1L;
    public static final String AWAITING_FIRST_RESPONSE = "AWAITING_FIRST_RESPONSE";
    public static final String READY_TO_PICK = "READY_TO_PICK";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String AWAITING_CUSTOMER_RESPONSE = "AWAITING_CUSTOMER_RESPONSE";
    public static final String CANCELLED = "CANCELLED";
    public static final String COMPLETED = "COMPLETED";
    private static final List<StatusInput> REQUIRED_STATUSES = List.of(
            new StatusInput(AWAITING_FIRST_RESPONSE, "Awaiting first response", true, false, 0),
            new StatusInput(READY_TO_PICK, "Ready to pick", false, false, 1),
            new StatusInput(IN_PROGRESS, "In progress", false, false, 2),
            new StatusInput(AWAITING_CUSTOMER_RESPONSE, "Awaiting customer response", false, false, 3),
            new StatusInput(CANCELLED, "Cancelled", false, true, 4),
            new StatusInput(COMPLETED, "Completed", false, true, 5));
    @Inject AuthService auth;

    @Transactional
    public List<WorkItemDefinitionView> listDefinitions(String token) {
        globalAdministrator(token);
        return WorkItemDefinitionEntity.<WorkItemDefinitionEntity>list("order by globalScope desc, ownerTenant.companyCode, type")
                .stream().map(this::view).toList();
    }

    @Transactional
    public List<WorkItemDefinitionView> effective(String token, Long requestedTenantId) {
        ApplicationUserEntity actor = authorizedReader(token);
        Long tenantId = actor.role == UserRole.GLOBAL_ADMIN
                ? (requestedTenantId == null ? actor.tenant.id : requestedTenantId)
                : actor.tenant.id;
        if (actor.role != UserRole.GLOBAL_ADMIN && requestedTenantId != null && !requestedTenantId.equals(actor.tenant.id)) {
            throw new ForbiddenException("Tenant is outside your scope");
        }
        requireTenant(tenantId);
        return effectiveEntities(tenantId).stream().map(this::view).toList();
    }

    @Transactional
    public WorkItemDefinitionView create(String token, DefinitionInput input) {
        globalAdministrator(token);
        validate(input);
        TenantEntity owner = input.globalScope() ? requireTenant(CASIQ_TENANT_ID) : requireTenant(input.tenantId());
        String normalizedType = normalize(input.type());
        ensureUnique(owner.id, normalizedType, null);

        WorkItemDefinitionEntity global = null;
        if (!input.globalScope()) {
            global = WorkItemDefinitionEntity.find("globalScope = true and normalizedType = ?1", normalizedType).firstResult();
            if (global == null) throw new BadRequestException("A CASIQ-wide work item with this type must exist before a tenant override");
        }
        Instant now = Instant.now();
        WorkItemDefinitionEntity definition = new WorkItemDefinitionEntity();
        definition.ownerTenant = owner;
        definition.type = canonical(input.type());
        definition.normalizedType = normalizedType;
        definition.displayName = input.displayName().trim();
        definition.globalScope = input.globalScope();
        definition.overridesDefinition = global;
        definition.active = input.active();
        definition.createdAt = now;
        definition.updatedAt = now;
        Panache.getEntityManager().persist(definition);
        replaceGraph(definition, input);
        return view(definition);
    }

    @Transactional
    public WorkItemDefinitionView update(String token, Long id, DefinitionInput input) {
        globalAdministrator(token);
        validate(input);
        WorkItemDefinitionEntity definition = requireDefinition(id);
        Long requestedOwner = input.globalScope() ? CASIQ_TENANT_ID : input.tenantId();
        if (definition.globalScope != input.globalScope() || !definition.ownerTenant.id.equals(requestedOwner)) {
            throw new BadRequestException("Work item scope and owner tenant cannot be changed");
        }
        String normalizedType = normalize(input.type());
        if (!definition.normalizedType.equals(normalizedType)) {
            throw new BadRequestException("Work item type cannot be changed; create another definition instead");
        }
        ensureUnique(definition.ownerTenant.id, normalizedType, id);
        if (!definition.globalScope) {
            WorkItemDefinitionEntity global = WorkItemDefinitionEntity.find(
                    "globalScope = true and normalizedType = ?1", normalizedType).firstResult();
            if (global == null) throw new BadRequestException("A CASIQ-wide work item with this type must exist");
            definition.overridesDefinition = global;
        }
        definition.type = canonical(input.type());
        definition.normalizedType = normalizedType;
        definition.displayName = input.displayName().trim();
        definition.active = input.active();
        definition.updatedAt = Instant.now();
        replaceGraph(definition, input);
        return view(definition);
    }

    @Transactional
    public WorkItemDefinitionEntity requireEffective(Long definitionId, Long tenantId) {
        return effectiveEntities(tenantId).stream().filter(item -> item.id.equals(definitionId)).findFirst()
                .orElseThrow(() -> new BadRequestException("Selected work item is not available to this tenant"));
    }

    private List<WorkItemDefinitionEntity> effectiveEntities(Long tenantId) {
        Map<String, WorkItemDefinitionEntity> effective = WorkItemDefinitionEntity
                .<WorkItemDefinitionEntity>list("globalScope = true and active = true order by type")
                .stream().collect(Collectors.toMap(item -> item.normalizedType, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        WorkItemDefinitionEntity.<WorkItemDefinitionEntity>list(
                "globalScope = false and ownerTenant.id = ?1 order by type", tenantId)
                .forEach(override -> {
                    if (override.active) effective.put(override.normalizedType, override);
                    else effective.remove(override.normalizedType);
                });
        return new ArrayList<>(effective.values());
    }

    private void replaceGraph(WorkItemDefinitionEntity definition, DefinitionInput input) {
        Map<String, WorkItemStatusEntity> existingStatuses = WorkItemStatusEntity
                .<WorkItemStatusEntity>list("definition.id", definition.id).stream()
                .collect(Collectors.toMap(status -> status.normalizedCode, Function.identity()));
        Map<String, WorkItemTransitionEntity> existingTransitions = WorkItemTransitionEntity
                .<WorkItemTransitionEntity>list("definition.id", definition.id).stream()
                .collect(Collectors.toMap(transition ->
                        transition.fromStatus.normalizedCode + "->" + transition.toStatus.normalizedCode,
                        Function.identity()));
        Map<String, WorkItemStatusEntity> statuses = new HashMap<>();
        for (StatusInput source : REQUIRED_STATUSES) {
            String normalizedCode = normalize(source.code());
            WorkItemStatusEntity status = existingStatuses.remove(normalizedCode);
            boolean newStatus = status == null;
            if (newStatus) status = new WorkItemStatusEntity();
            status.definition = definition;
            status.code = canonical(source.code());
            status.normalizedCode = normalizedCode;
            status.displayName = source.displayName().trim();
            status.initialStatus = source.initialStatus();
            status.terminalStatus = source.terminalStatus();
            status.sortOrder = source.sortOrder();
            if (newStatus) Panache.getEntityManager().persist(status);
            statuses.put(status.normalizedCode, status);
        }
        for (WorkItemStatusEntity removed : existingStatuses.values()) {
            if (WorkItemExecutionEntity.count("currentStatus.id", removed.id) > 0) {
                throw new WebApplicationException(
                        "Status " + removed.code + " is in use and cannot be removed", 409);
            }
        }
        for (TransitionInput source : input.transitions() == null ? List.<TransitionInput>of() : input.transitions()) {
            String key = normalize(source.fromStatus()) + "->" + normalize(source.toStatus());
            WorkItemTransitionEntity transition = existingTransitions.remove(key);
            boolean newTransition = transition == null;
            if (newTransition) transition = new WorkItemTransitionEntity();
            transition.definition = definition;
            transition.fromStatus = statuses.get(normalize(source.fromStatus()));
            transition.toStatus = statuses.get(normalize(source.toStatus()));
            transition.label = source.label().trim();
            if (newTransition) Panache.getEntityManager().persist(transition);
        }
        existingTransitions.values().forEach(WorkItemTransitionEntity::delete);
        existingStatuses.values().forEach(WorkItemStatusEntity::delete);
        Panache.getEntityManager().flush();
    }

    private void validate(DefinitionInput input) {
        if (input == null) throw new BadRequestException("Work item definition is required");
        if (blank(input.type()) || !input.type().trim().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new BadRequestException("Type must contain only letters, numbers, underscores, or hyphens");
        if (blank(input.displayName()) || input.displayName().trim().length() > 160)
            throw new BadRequestException("Display name is required and must not exceed 160 characters");
        if (!input.globalScope() && input.tenantId() == null) throw new BadRequestException("tenantId is required for an override");
        Map<String, StatusInput> statuses = REQUIRED_STATUSES.stream()
                .collect(Collectors.toMap(status -> normalize(status.code()), Function.identity()));
        Map<String, Set<String>> edges = new HashMap<>();
        Set<String> pairs = new HashSet<>();
        for (TransitionInput transition : input.transitions() == null ? List.<TransitionInput>of() : input.transitions()) {
            String from = normalize(transition.fromStatus()), to = normalize(transition.toStatus());
            if (!statuses.containsKey(from) || !statuses.containsKey(to)) throw new BadRequestException("Transitions must reference defined statuses");
            if (statuses.get(from).terminalStatus()) throw new BadRequestException("Terminal statuses cannot have outgoing transitions");
            if (!pairs.add(from + "->" + to)) throw new BadRequestException("Duplicate transition");
            if (blank(transition.label())) throw new BadRequestException("Every transition requires a label");
            edges.computeIfAbsent(from, ignored -> new HashSet<>()).add(to);
        }
        String initial = normalize(AWAITING_FIRST_RESPONSE);
        Set<String> reached = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(); queue.add(initial);
        while (!queue.isEmpty()) { String next = queue.remove(); if (reached.add(next)) queue.addAll(edges.getOrDefault(next, Set.of())); }
        if (reached.size() != statuses.size()) throw new BadRequestException("Every status must be reachable from the initial status");
    }

    private WorkItemDefinitionView view(WorkItemDefinitionEntity definition) {
        definition.ownerTenant = TenantEntity.findById(definition.ownerTenant.id);
        List<WorkItemDefinitionView.StatusView> statuses = WorkItemStatusEntity.<WorkItemStatusEntity>list(
                "definition.id = ?1 order by sortOrder, code", definition.id).stream()
                .map(s -> new WorkItemDefinitionView.StatusView(s.id, s.code, s.displayName, s.initialStatus, s.terminalStatus, s.sortOrder)).toList();
        List<WorkItemDefinitionView.TransitionView> transitions = WorkItemTransitionEntity.<WorkItemTransitionEntity>list(
                "definition.id = ?1 order by label", definition.id).stream()
                .map(t -> new WorkItemDefinitionView.TransitionView(t.id, t.fromStatus.code, t.toStatus.code, t.label)).toList();
        return new WorkItemDefinitionView(definition.id, definition.ownerTenant.id, definition.ownerTenant.companyCode,
                definition.type, definition.displayName, definition.globalScope,
                definition.overridesDefinition == null ? null : definition.overridesDefinition.id,
                definition.active, statuses, transitions, definition.createdAt, definition.updatedAt);
    }

    private ApplicationUserEntity authorizedReader(String token) {
        ApplicationUserEntity actor = auth.requireEntity(token); auth.requireCompletedPasswordChange(actor);
        return actor;
    }
    private ApplicationUserEntity globalAdministrator(String token) {
        ApplicationUserEntity actor = auth.requireEntity(token); auth.requireCompletedPasswordChange(actor);
        if (actor.role != UserRole.GLOBAL_ADMIN) throw new ForbiddenException("GLOBAL_ADMIN role required");
        return actor;
    }
    private TenantEntity requireTenant(Long id) {
        if (id == null) throw new BadRequestException("tenantId is required");
        TenantEntity tenant = TenantEntity.findById(id);
        if (tenant == null) throw new NotFoundException("Tenant not found");
        return tenant;
    }
    private WorkItemDefinitionEntity requireDefinition(Long id) {
        WorkItemDefinitionEntity definition = WorkItemDefinitionEntity.findById(id);
        if (definition == null) throw new NotFoundException("Work item definition not found");
        definition.ownerTenant = TenantEntity.findById(definition.ownerTenant.id);
        return definition;
    }
    private void ensureUnique(Long ownerId, String type, Long excluded) {
        long count = excluded == null
                ? WorkItemDefinitionEntity.count("ownerTenant.id = ?1 and normalizedType = ?2", ownerId, type)
                : WorkItemDefinitionEntity.count("ownerTenant.id = ?1 and normalizedType = ?2 and id <> ?3", ownerId, type, excluded);
        if (count > 0) throw new WebApplicationException("Work item type already exists for this scope", 409);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static String canonical(String value) { return value.trim().toUpperCase(Locale.ROOT).replace('-', '_'); }

    public record DefinitionInput(Long tenantId, boolean globalScope, String type, String displayName,
                                  boolean active, List<StatusInput> statuses, List<TransitionInput> transitions) {}
    public record StatusInput(String code, String displayName, boolean initialStatus, boolean terminalStatus, int sortOrder) {}
    public record TransitionInput(String fromStatus, String toStatus, String label) {}
}
