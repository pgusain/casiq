package com.casiq.workitem.service;

import com.casiq.usermanagement.domain.UserRole;
import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.usermanagement.service.AuthService;
import com.casiq.workitem.api.WorkItemWorkflowView;
import com.casiq.workitem.persistence.*;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.sql.Timestamp;
import java.util.*;

@ApplicationScoped
public class WorkItemWorkflowService {
    private static final Logger LOG = Logger.getLogger(WorkItemWorkflowService.class);
    @Inject AuthService auth;
    @Inject WorkItemDefinitionService definitions;

    @Transactional
    public void initialize(UUID workAccountId, String workAccountEmail,
                           TenantEntity tenant, WorkItemDefinitionEntity definition) {
        WorkItemStatusEntity initial = WorkItemStatusEntity.find(
                "definition.id = ?1 and initialStatus = true", definition.id).firstResult();
        if (initial == null) throw new BadRequestException("Work item definition has no initial status");
        WorkItemExecutionEntity execution = new WorkItemExecutionEntity();
        execution.tenant = tenant;
        execution.workAccountId = workAccountId;
        execution.workAccountEmail = workAccountEmail;
        execution.workAccountNormalizedEmail = normalize(workAccountEmail);
        execution.definition = definition;
        execution.currentStatus = initial;
        execution.createdAt = Instant.now();
        execution.updatedAt = execution.createdAt;
        Panache.getEntityManager().persist(execution);
        LOG.infof("Initialized account-level work-item execution workAccountId=%s definitionId=%s status=%s",
                workAccountId, definition.id, initial.code);
    }

    @Transactional
    public void changeDefinition(UUID workAccountId, String workAccountEmail,
                                 WorkItemDefinitionEntity definition) {
        WorkItemExecutionEntity execution = WorkItemExecutionEntity.find(
                "workAccountId = ?1 and conversationId is null", workAccountId).firstResult();
        if (execution == null) return;
        execution.workAccountEmail = workAccountEmail;
        execution.workAccountNormalizedEmail = normalize(workAccountEmail);
        if (execution.definition.id.equals(definition.id)) {
            execution.updatedAt = Instant.now();
            return;
        }
        WorkItemStatusEntity initial = WorkItemStatusEntity.find(
                "definition.id = ?1 and initialStatus = true", definition.id).firstResult();
        if (initial == null) throw new BadRequestException("Work item definition has no initial status");
        execution.definition = definition;
        execution.currentStatus = initial;
        execution.updatedAt = Instant.now();
        LOG.infof("Changed account-level work-item definition workAccountId=%s definitionId=%s status=%s",
                workAccountId, definition.id, initial.code);
    }

    @Transactional
    public List<WorkItemWorkflowView.Assignment> listAssignments(String token, UUID requestedTenantId) {
        ApplicationUserEntity actor = administrator(token);
        TenantEntity tenant = targetTenant(actor, requestedTenantId);
        List<WorkItemWorkflowView.Assignment> result = new ArrayList<>();
        WorkItemStatusAssignmentEntity.<WorkItemStatusAssignmentEntity>list(
                "tenant.id = ?1 order by definition.type, status.sortOrder, user.username", tenant.id)
                .forEach(a -> result.add(statusAssignmentView(a)));
        WorkItemTransitionAssignmentEntity.<WorkItemTransitionAssignmentEntity>list(
                "tenant.id = ?1 order by definition.type, transition.label, user.username", tenant.id)
                .forEach(a -> result.add(transitionAssignmentView(a)));
        return result;
    }

    @Transactional
    public WorkItemWorkflowView.Assignment assign(String token, AssignmentInput input) {
        ApplicationUserEntity actor = administrator(token);
        TenantEntity tenant = targetTenant(actor, input.tenantId());
        if ((input.statusId() == null) == (input.transitionId() == null)) {
            throw new BadRequestException("Assign exactly one status or transition");
        }
        WorkItemDefinitionEntity definition = definitions.requireEffective(input.definitionId(), tenant.id);
        ApplicationUserEntity user = ApplicationUserEntity.findById(input.userId());
        if (user == null || !user.active) throw new NotFoundException("Active user not found");
        user.tenant = TenantEntity.findById(user.tenant.id);
        if (!user.tenant.id.equals(tenant.id)) throw new BadRequestException("Assigned user must belong to the selected tenant");
        Instant now = Instant.now();

        if (input.statusId() != null) {
            WorkItemStatusEntity status = WorkItemStatusEntity.findById(input.statusId());
            requireStatusDefinition(status, definition.id);
            if (WorkItemStatusAssignmentEntity.count(
                    "tenant.id = ?1 and status.id = ?2 and user.id = ?3", tenant.id, status.id, user.id) > 0) {
                throw new WebApplicationException("Status is already assigned to this user", 409);
            }
            WorkItemStatusAssignmentEntity assignment = new WorkItemStatusAssignmentEntity();
            assignment.tenant = tenant; assignment.definition = definition; assignment.status = status;
            assignment.user = user; assignment.createdBy = actor; assignment.createdAt = now;
            Panache.getEntityManager().persist(assignment);
            LOG.infof("Created work-item status assignment tenantId=%s definitionId=%s statusId=%s userId=%s actorId=%s",
                    tenant.id, definition.id, status.id, user.id, actor.id);
            return statusAssignmentView(assignment);
        }

        WorkItemTransitionEntity transition = WorkItemTransitionEntity.findById(input.transitionId());
        requireTransitionDefinition(transition, definition.id);
        if (WorkItemTransitionAssignmentEntity.count(
                "tenant.id = ?1 and transition.id = ?2 and user.id = ?3", tenant.id, transition.id, user.id) > 0) {
            throw new WebApplicationException("Transition is already assigned to this user", 409);
        }
        WorkItemTransitionAssignmentEntity assignment = new WorkItemTransitionAssignmentEntity();
        assignment.tenant = tenant; assignment.definition = definition; assignment.transition = transition;
        assignment.user = user; assignment.createdBy = actor; assignment.createdAt = now;
        Panache.getEntityManager().persist(assignment);
        LOG.infof("Created work-item transition assignment tenantId=%s definitionId=%s transitionId=%s userId=%s actorId=%s",
                tenant.id, definition.id, transition.id, user.id, actor.id);
        return transitionAssignmentView(assignment);
    }

    @Transactional
    public void removeAssignment(String token, String type, UUID id) {
        ApplicationUserEntity actor = administrator(token);
        if ("STATUS".equalsIgnoreCase(type)) {
            WorkItemStatusAssignmentEntity assignment = WorkItemStatusAssignmentEntity.findById(id);
            if (assignment == null) throw new NotFoundException("Assignment not found");
            assertTenant(actor, assignment.tenant.id);
            assignment.delete();
            LOG.infof("Removed work-item status assignment assignmentId=%s actorId=%s", id, actor.id);
        } else if ("TRANSITION".equalsIgnoreCase(type)) {
            WorkItemTransitionAssignmentEntity assignment = WorkItemTransitionAssignmentEntity.findById(id);
            if (assignment == null) throw new NotFoundException("Assignment not found");
            assertTenant(actor, assignment.tenant.id);
            assignment.delete();
            LOG.infof("Removed work-item transition assignment assignmentId=%s actorId=%s", id, actor.id);
        } else throw new BadRequestException("Assignment type must be STATUS or TRANSITION");
    }

    @Transactional
    public WorkItemWorkflowView.WorkPage myWork(
            String token,
            String workItemType,
            String status,
            String email,
            boolean includeTerminal,
            int requestedPage,
            int requestedSize,
            String requestedSortBy,
            String requestedSortDirection) {
        ApplicationUserEntity actor = authenticated(token);
        int page = Math.max(0, requestedPage);
        int size = Math.max(1, Math.min(requestedSize, 100));
        String sortBy = sortBy(requestedSortBy);
        String sortDirection = sortDirection(requestedSortDirection);
        StringBuilder query = new StringBuilder("tenant.id = :tenantId");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", actor.tenant.id);
        if (!includeTerminal) query.append(" and currentStatus.terminalStatus = false");
        if (hasText(workItemType)) {
            query.append(" and definition.normalizedType = :workItemType");
            parameters.put("workItemType", normalize(workItemType));
        }
        if (hasText(status)) {
            query.append(" and currentStatus.normalizedCode = :status");
            parameters.put("status", normalize(status));
        }
        if (hasText(email)) {
            query.append(" and workAccountNormalizedEmail like :email");
            parameters.put("email", normalize(email) + "%");
        }
        query.append(" order by ")
                .append(sortExpression(sortBy))
                .append(' ')
                .append(sortDirection)
                .append(", id ")
                .append(sortDirection);
        List<WorkItemExecutionEntity> executions =
                WorkItemExecutionEntity.list(query.toString(), parameters);
        List<WorkItemExecutionEntity> visible = executions.stream()
                .filter(execution -> visibleTo(execution, actor, includeTerminal))
                .toList();
        long total = visible.size();
        int from = (int) Math.min((long) page * size, visible.size());
        int to = Math.min(from + size, visible.size());
        List<WorkItemWorkflowView.Execution> items = visible.subList(from, to).stream()
                .map(execution -> executionView(execution, actor))
                .toList();
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        LOG.debugf("Listed user work actorId=%s tenantId=%s candidates=%d visible=%d returned=%d page=%d size=%d sortBy=%s sortDirection=%s type=%s status=%s emailFilter=%s includeTerminal=%s",
                actor.id, actor.tenant.id, executions.size(), visible.size(), items.size(),
                page, size, sortBy, sortDirection,
                workItemType, status, hasText(email), includeTerminal);
        return new WorkItemWorkflowView.WorkPage(
                items, page, size, total, totalPages, sortBy, sortDirection);
    }

    @Transactional
    public WorkItemWorkflowView.Detail detail(String token, UUID executionId) {
        ApplicationUserEntity actor = authenticated(token);
        WorkItemExecutionEntity execution = WorkItemExecutionEntity.findById(executionId);
        if (execution == null) throw new NotFoundException("Work item execution not found");
        if (!execution.tenant.id.equals(actor.tenant.id)) {
            throw new ForbiddenException("Work item is outside your tenant");
        }
        if (!visibleTo(execution, actor, true)) {
            throw new ForbiddenException("Work item is not assigned to you");
        }
        LOG.infof("Opened work-item detail executionId=%s conversationId=%s actorId=%s",
                execution.id, execution.conversationId, actor.id);
        return new WorkItemWorkflowView.Detail(
                executionView(execution, actor),
                conversationView(execution));
    }

    @Transactional
    public WorkItemWorkflowView.Execution perform(String token, UUID executionId, UUID transitionId) {
        ApplicationUserEntity actor = authenticated(token);
        WorkItemExecutionEntity execution = WorkItemExecutionEntity.find("id", executionId)
                .withLock(LockModeType.PESSIMISTIC_WRITE).firstResult();
        if (execution == null) throw new NotFoundException("Work item execution not found");
        if (!execution.tenant.id.equals(actor.tenant.id)) throw new ForbiddenException("Work item is outside your tenant");
        WorkItemTransitionEntity transition = WorkItemTransitionEntity.findById(transitionId);
        if (transition == null || !transition.definition.id.equals(execution.definition.id)
                || !transition.fromStatus.id.equals(execution.currentStatus.id)) {
            throw new WebApplicationException("Transition is not available from the current status", 409);
        }
        if (allowedTransitions(execution, actor).stream().noneMatch(candidate -> candidate.id.equals(transition.id))) {
            throw new ForbiddenException("This transition is not assigned to you");
        }

        WorkItemActivityEntity activity = new WorkItemActivityEntity();
        activity.tenant = execution.tenant;
        activity.execution = execution;
        activity.transition = transition;
        activity.performedBy = actor;
        activity.transitionLabel = transition.label;
        activity.fromStatusCode = transition.fromStatus.code;
        activity.toStatusCode = transition.toStatus.code;
        activity.performedAt = Instant.now();
        Panache.getEntityManager().persist(activity);
        execution.currentStatus = transition.toStatus;
        execution.updatedAt = activity.performedAt;
        LOG.infof("Performed work-item transition executionId=%s transitionId=%s actorId=%s fromStatus=%s toStatus=%s",
                execution.id, transition.id, actor.id,
                activity.fromStatusCode, activity.toStatusCode);
        return executionView(execution, actor);
    }

    private List<WorkItemTransitionEntity> allowedTransitions(WorkItemExecutionEntity execution, ApplicationUserEntity actor) {
        List<WorkItemTransitionEntity> outgoing = WorkItemTransitionEntity.list(
                "definition.id = ?1 and fromStatus.id = ?2 order by label",
                execution.definition.id, execution.currentStatus.id);
        boolean ownsStatus = WorkItemStatusAssignmentEntity.count(
                "tenant.id = ?1 and status.id = ?2 and user.id = ?3",
                execution.tenant.id, execution.currentStatus.id, actor.id) > 0;
        if (ownsStatus) return outgoing;
        Set<UUID> assigned = new HashSet<>(WorkItemTransitionAssignmentEntity.<WorkItemTransitionAssignmentEntity>list(
                "tenant.id = ?1 and user.id = ?2 and transition.fromStatus.id = ?3",
                execution.tenant.id, actor.id, execution.currentStatus.id).stream().map(a -> a.transition.id).toList());
        return outgoing.stream().filter(t -> assigned.contains(t.id)).toList();
    }

    private boolean visibleTo(
            WorkItemExecutionEntity execution,
            ApplicationUserEntity actor,
            boolean includeTerminal) {
        if (!execution.currentStatus.terminalStatus) {
            return !allowedTransitions(execution, actor).isEmpty();
        }
        if (!includeTerminal) return false;
        return WorkItemActivityEntity.count(
                "execution.id = ?1 and performedBy.id = ?2", execution.id, actor.id) > 0
                || WorkItemStatusAssignmentEntity.count(
                "tenant.id = ?1 and status.id = ?2 and user.id = ?3",
                execution.tenant.id, execution.currentStatus.id, actor.id) > 0;
    }

    private WorkItemWorkflowView.Execution executionView(WorkItemExecutionEntity execution, ApplicationUserEntity actor) {
        List<WorkItemWorkflowView.Transition> transitions = allowedTransitions(execution, actor).stream()
                .map(t -> new WorkItemWorkflowView.Transition(t.id, t.label, t.fromStatus.code, t.toStatus.code)).toList();
        List<WorkItemWorkflowView.Activity> activities = WorkItemActivityEntity.<WorkItemActivityEntity>list(
                "execution.id = ?1 order by performedAt desc", execution.id).stream()
                .map(a -> new WorkItemWorkflowView.Activity(a.transitionLabel, a.fromStatusCode, a.toStatusCode,
                        a.performedBy.id, a.performedBy.username, a.performedAt)).toList();
        return new WorkItemWorkflowView.Execution(execution.id, execution.workAccountId,
                execution.conversationId, execution.workAccountEmail,
                execution.definition.id, execution.definition.type, execution.definition.displayName,
                execution.currentStatus.id, execution.currentStatus.code, execution.currentStatus.displayName,
                execution.currentStatus.terminalStatus,
                transitions, activities, execution.updatedAt);
    }

    private WorkItemWorkflowView.Conversation conversationView(WorkItemExecutionEntity execution) {
        if (execution.conversationId == null) return null;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = Panache.getEntityManager().createNativeQuery("""
                SELECT subject, sender, recipients, sent_at, snippet,
                       content_text, content_html, direction
                FROM work_account_conversation
                WHERE id = ?1
                  AND tenant_id = ?2
                """)
                .setParameter(1, execution.conversationId)
                .setParameter(2, execution.tenant.id)
                .getResultList();
        if (rows.isEmpty()) return null;
        Object[] row = rows.get(0);
        return new WorkItemWorkflowView.Conversation(
                execution.conversationId,
                nullableString(row[0]),
                nullableString(row[1]),
                nullableString(row[2]),
                instant(row[3]),
                nullableString(row[4]),
                nullableString(row[5]),
                nullableString(row[6]),
                nullableString(row[7]));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String sortBy(String value) {
        String requested = value == null ? "" : value.trim();
        return switch (requested) {
            case "createdAt", "email", "workItemType", "status", "updatedAt" -> requested;
            default -> "updatedAt";
        };
    }

    private static String sortDirection(String value) {
        return "asc".equalsIgnoreCase(value) ? "asc" : "desc";
    }

    private static String sortExpression(String sortBy) {
        return switch (sortBy) {
            case "createdAt" -> "createdAt";
            case "email" -> "workAccountNormalizedEmail";
            case "workItemType" -> "definition.normalizedType";
            case "status" -> "currentStatus.sortOrder";
            default -> "updatedAt";
        };
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return Instant.parse(String.valueOf(value));
    }

    private WorkItemWorkflowView.Assignment statusAssignmentView(WorkItemStatusAssignmentEntity a) {
        return new WorkItemWorkflowView.Assignment(a.id, "STATUS", a.tenant.id, a.definition.id, a.definition.type,
                a.status.id, a.status.code, null, null, a.user.id, a.user.username, a.createdAt);
    }
    private WorkItemWorkflowView.Assignment transitionAssignmentView(WorkItemTransitionAssignmentEntity a) {
        return new WorkItemWorkflowView.Assignment(a.id, "TRANSITION", a.tenant.id, a.definition.id, a.definition.type,
                null, null, a.transition.id, a.transition.label, a.user.id, a.user.username, a.createdAt);
    }
    private ApplicationUserEntity authenticated(String token) {
        ApplicationUserEntity actor = auth.requireEntity(token); auth.requireCompletedPasswordChange(actor); return actor;
    }
    private ApplicationUserEntity administrator(String token) {
        ApplicationUserEntity actor = authenticated(token);
        if (actor.role != UserRole.GLOBAL_ADMIN && actor.role != UserRole.ADMIN) throw new ForbiddenException("Administrator role required");
        return actor;
    }
    private TenantEntity targetTenant(ApplicationUserEntity actor, UUID requestedTenantId) {
        UUID tenantId = actor.role == UserRole.GLOBAL_ADMIN ? requestedTenantId : actor.tenant.id;
        if (actor.role != UserRole.GLOBAL_ADMIN && requestedTenantId != null && !requestedTenantId.equals(actor.tenant.id)) {
            throw new ForbiddenException("Tenant is outside your scope");
        }
        if (tenantId == null) throw new BadRequestException("tenantId is required for GLOBAL_ADMIN");
        TenantEntity tenant = TenantEntity.findById(tenantId);
        if (tenant == null || !tenant.active) throw new NotFoundException("Active tenant not found");
        return tenant;
    }
    private void assertTenant(ApplicationUserEntity actor, UUID tenantId) {
        if (actor.role != UserRole.GLOBAL_ADMIN && !actor.tenant.id.equals(tenantId)) {
            throw new ForbiddenException("Assignment is outside your tenant");
        }
    }
    private static void requireStatusDefinition(WorkItemStatusEntity status, UUID definitionId) {
        if (status == null || !status.definition.id.equals(definitionId)) throw new BadRequestException("Status does not belong to the work item");
    }
    private static void requireTransitionDefinition(WorkItemTransitionEntity transition, UUID definitionId) {
        if (transition == null || !transition.definition.id.equals(definitionId)) throw new BadRequestException("Transition does not belong to the work item");
    }

    public record AssignmentInput(UUID tenantId, UUID definitionId, UUID statusId, UUID transitionId, UUID userId) {}
}
