package com.casiq.workitem.service;

import com.casiq.storage.AttachmentStorage;
import com.casiq.usermanagement.domain.UserRole;
import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.usermanagement.service.AuthService;
import com.casiq.workitem.api.WorkItemWorkflowView;
import com.casiq.workitem.archive.WorkItemArchiveCodec;
import com.casiq.workitem.archive.WorkItemArchivePayload;
import com.casiq.workitem.persistence.*;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.sql.Timestamp;
import java.util.*;

@ApplicationScoped
public class WorkItemWorkflowService {
    private static final Logger LOG = Logger.getLogger(WorkItemWorkflowService.class);
    @Inject AuthService auth;
    @Inject WorkItemDefinitionService definitions;
    @Inject AttachmentStorage attachmentStorage;
    @Inject WorkItemArchiveCodec archiveCodec;
    @Inject WorkItemNumberService workItemNumbers;
    @Inject Instance<WorkItemEmailContentResolver> emailContentResolvers;
    @ConfigProperty(name = "casiq.attachment-storage.max-bytes") long maxAttachmentBytes;
    @ConfigProperty(name = "casiq.work-item.provider-read-enabled", defaultValue = "true")
    boolean providerReadEnabled;
    @ConfigProperty(name = "casiq.work-item.email-cache-seconds", defaultValue = "300")
    long emailCacheSeconds;

    @Transactional
    public void initialize(Long workAccountId, String workAccountEmail,
                           TenantEntity tenant, WorkItemDefinitionEntity definition) {
        WorkItemStatusEntity initial = WorkItemStatusEntity.find(
                "definition.id = ?1 and initialStatus = true", definition.id).firstResult();
        if (initial == null) throw new BadRequestException("Work item definition has no initial status");
        WorkItemExecutionEntity execution = new WorkItemExecutionEntity();
        execution.tenant = tenant;
        execution.workItemNumber = workItemNumbers.next(tenant);
        execution.workAccountId = workAccountId;
        execution.workAccountEmail = workAccountEmail;
        execution.workAccountNormalizedEmail = normalize(workAccountEmail);
        execution.definition = definition;
        execution.currentStatus = initial;
        execution.createdAt = Instant.now();
        execution.archiveNextAttemptAt = execution.createdAt;
        execution.updatedAt = execution.createdAt;
        Panache.getEntityManager().persist(execution);
        LOG.infof("Initialized account-level work-item execution workAccountId=%s definitionId=%s status=%s",
                workAccountId, definition.id, initial.code);
    }

    @Transactional
    public void changeDefinition(Long workAccountId, String workAccountEmail,
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
    public List<WorkItemWorkflowView.Assignment> listAssignments(String token, Long requestedTenantId) {
        ApplicationUserEntity actor = administrator(token);
        MDC.put("tenantCode", requestedTenantId == null ? String.valueOf(actor.tenant.id) : String.valueOf(requestedTenantId));
        try {
            LOG.debugf("Listing work-item assignments actorId=%s requestedTenantId=%s", String.valueOf(actor.id), requestedTenantId);
            TenantEntity tenant = targetTenant(actor, requestedTenantId);
            List<WorkItemWorkflowView.Assignment> result = new ArrayList<>();
            WorkItemStatusAssignmentEntity.<WorkItemStatusAssignmentEntity>list(
                    "tenant.id = ?1 order by definition.type, status.sortOrder, user.username", tenant.id)
                    .forEach(a -> result.add(statusAssignmentView(a)));
            WorkItemTransitionAssignmentEntity.<WorkItemTransitionAssignmentEntity>list(
                    "tenant.id = ?1 order by definition.type, transition.label, user.username", tenant.id)
                    .forEach(a -> result.add(transitionAssignmentView(a)));
            return result;
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error listing work-item assignments actorId=%s requestedTenantId=%s", String.valueOf(actor.id), requestedTenantId, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public WorkItemWorkflowView.Assignment assign(String token, AssignmentInput input) {
        ApplicationUserEntity actor = administrator(token);
        MDC.put("tenantCode", input != null && input.tenantId() != null ? String.valueOf(input.tenantId()) : "unknown");
        try {
            LOG.debugf("Assigning work item actorId=%s tenantId=%s definitionId=%s statusId=%s transitionId=%s userId=%s",
                    String.valueOf(actor.id), input.tenantId(), input.definitionId(), input.statusId(), input.transitionId(), input.userId());
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
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error assigning work item actorId=%s tenantId=%s definitionId=%s userId=%s", String.valueOf(actor.id), input != null ? input.tenantId() : null, input != null ? input.definitionId() : null, input != null ? input.userId() : null, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public void removeAssignment(String token, String type, Long id) {
        ApplicationUserEntity actor = administrator(token);
        MDC.put("tenantCode", String.valueOf(actor.tenant.id));
        try {
            LOG.debugf("Removing assignment actorId=%s type=%s assignmentId=%s", String.valueOf(actor.id), type, id);
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
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error removing assignment actorId=%s type=%s assignmentId=%s", String.valueOf(actor.id), type, id, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public WorkItemWorkflowView.WorkPage myWork(
            String token,
            String queueScope,
            String workItemType,
            String status,
            String email,
            boolean includeTerminal,
            int requestedPage,
            int requestedSize,
            String requestedSortBy,
            String requestedSortDirection) {
        ApplicationUserEntity actor = authenticated(token);
        MDC.put("tenantCode", String.valueOf(actor.tenant.id));
        try {
            LOG.debugf("Loading myWork actorId=%s queueScope=%s includeTerminal=%s requestedPage=%s requestedSize=%s", String.valueOf(actor.id), queueScope, includeTerminal, requestedPage, requestedSize);
            int page = Math.max(0, requestedPage);
        int size = Math.max(1, Math.min(requestedSize, 100));
        String sortBy = sortBy(requestedSortBy);
        String sortDirection = sortDirection(requestedSortDirection);
        StringBuilder query = new StringBuilder("tenant.id = :tenantId");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantId", actor.tenant.id);
        appendQueueScope(query, parameters, queueScope, actor.id);
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
                .filter(execution -> visibleInQueue(
                        execution, actor, includeTerminal, queueScope))
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
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error loading myWork actorId=%s tenantId=%s", actor.id, actor.tenant.id, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public List<WorkItemWorkflowView.StatusCount> myWorkStatusSummary(
            String token,
            String queueScope,
            String workItemType,
            String email,
            boolean includeTerminal) {
        ApplicationUserEntity actor = authenticated(token);
        MDC.put("tenantCode", String.valueOf(actor.tenant.id));
        try {
            LOG.debugf("Summarizing user work actorId=%s tenantId=%s queueScope=%s type=%s emailFilter=%s includeTerminal=%s",
                    actor.id, actor.tenant.id, queueScope, workItemType, hasText(email), includeTerminal);
            StringBuilder query = new StringBuilder("tenant.id = :tenantId");
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("tenantId", actor.tenant.id);
            appendQueueScope(query, parameters, queueScope, actor.id);
            if (!includeTerminal) query.append(" and currentStatus.terminalStatus = false");
            if (hasText(workItemType)) {
                query.append(" and definition.normalizedType = :workItemType");
                parameters.put("workItemType", normalize(workItemType));
            }
            if (hasText(email)) {
                query.append(" and workAccountNormalizedEmail like :email");
                parameters.put("email", normalize(email) + "%");
            }
            List<WorkItemExecutionEntity> executions =
                    WorkItemExecutionEntity.list(query.toString(), parameters);
            Map<String, WorkItemWorkflowView.StatusCount> counts = new TreeMap<>();
            executions.stream()
                    .filter(execution -> visibleInQueue(
                            execution, actor, includeTerminal, queueScope))
                    .forEach(execution -> {
                        WorkItemStatusEntity status = execution.currentStatus;
                        counts.compute(status.code, (code, current) ->
                                new WorkItemWorkflowView.StatusCount(
                                        code,
                                        status.displayName,
                                        status.terminalStatus,
                                        current == null ? 1 : current.count() + 1));
                    });
            LOG.debugf("Summarized user work actorId=%s tenantId=%s statuses=%d type=%s emailFilter=%s includeTerminal=%s",
                    actor.id, actor.tenant.id, counts.size(), workItemType,
                    hasText(email), includeTerminal);
            return List.copyOf(counts.values());
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error summarizing myWork actorId=%s tenantId=%s", actor.id, actor.tenant.id, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public WorkItemWorkflowView.Detail detail(String token, Long executionId) {
        ApplicationUserEntity actor = authenticated(token);
        MDC.put("tenantCode", String.valueOf(actor.tenant.id));
        try {
            LOG.debugf("Opening work-item detail actorId=%s executionId=%s", actor.id, executionId);
            WorkItemExecutionEntity execution = accessibleExecution(actor, executionId);
            if (execution.dataMigrated) {
                WorkItemWorkflowView.Detail archived =
                        archivedDetail(execution, actor);
                LOG.infof(
                        "Opened archived work-item detail executionId=%s archiveKey=%s actorId=%s",
                        execution.id, execution.archiveStorageKey, actor.id);
                return archived;
            }
            LOG.infof("Opened work-item detail executionId=%s conversationId=%s actorId=%s",
                    execution.id, execution.initialCommunicationId, actor.id);
            List<WorkItemWorkflowView.Conversation> communications =
                    communications(execution);
            WorkItemWorkflowView.Conversation initial = communications.stream()
                    .filter(communication -> Objects.equals(
                            communication.id(), execution.initialCommunicationId))
                    .findFirst()
                    .orElse(communications.isEmpty() ? null : communications.get(0));
            return new WorkItemWorkflowView.Detail(
                    executionView(execution, actor),
                    initial,
                    communications,
                    documents(execution),
                    internalNotes(execution),
                    execution.currentStatus.terminalStatus
                            || execution.assignedUser != null
                            && !execution.assignedUser.id.equals(actor.id));
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error opening work-item detail actorId=%s executionId=%s", actor.id, executionId, e);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Transactional
    public WorkItemArchivePayload archivePayload(
            Long executionId,
            String owner) {
        WorkItemExecutionEntity execution =
                WorkItemExecutionEntity.findById(executionId);
        if (execution == null) {
            throw new NotFoundException("Work item execution not found");
        }
        Instant now = Instant.now();
        if (execution.dataMigrated) {
            throw new WebApplicationException(
                    "Work item has already been archived", 409);
        }
        if (!Objects.equals(owner, execution.archiveLockOwner)
                || execution.archiveLockedUntil == null
                || !execution.archiveLockedUntil.isAfter(now)) {
            throw new IllegalStateException(
                    "Work-item archive lease is missing or expired");
        }
        if (!WorkItemDefinitionService.COMPLETED.equals(
                execution.currentStatus.code)) {
            throw new WebApplicationException(
                    "Only completed work items can be archived", 409);
        }
        List<WorkItemWorkflowView.Conversation> communications =
                communications(execution);
        WorkItemWorkflowView.Conversation initial = communications.stream()
                .filter(communication -> Objects.equals(
                        communication.id(), execution.initialCommunicationId))
                .findFirst()
                .orElse(communications.isEmpty() ? null : communications.get(0));
        List<WorkItemArchivePayload.ArchivedDocument> archivedDocuments =
                WorkItemDocumentEntity.<WorkItemDocumentEntity>list(
                                "execution.id = ?1 order by createdAt, id",
                                execution.id)
                        .stream()
                        .map(document -> new WorkItemArchivePayload.ArchivedDocument(
                                documentView(document),
                                document.contentData != null
                                        ? document.contentData
                                        : attachmentStorage.get(
                                                execution.tenant.id,
                                                document.storageKey)))
                        .toList();
        WorkItemArchivePayload.ArchivedExecution archivedExecution =
                new WorkItemArchivePayload.ArchivedExecution(
                        execution.id,
                        execution.workItemNumber,
                        execution.tenant.id,
                        execution.workAccountId,
                        execution.initialCommunicationId,
                        execution.workAccountEmail,
                        execution.emailSubject,
                        execution.emailSender,
                        execution.emailRecipients,
                        execution.emailSentAt,
                        execution.definition.id,
                        execution.definition.type,
                        execution.definition.displayName,
                        execution.currentStatus.id,
                        execution.currentStatus.code,
                        execution.currentStatus.displayName,
                        execution.currentStatus.terminalStatus,
                        execution.assignedUser == null
                                ? null
                                : execution.assignedUser.id,
                        execution.assignedUser == null
                                ? null
                                : execution.assignedUser.username,
                        activities(execution),
                        execution.createdAt,
                        execution.updatedAt);
        WorkItemArchivePayload payload = new WorkItemArchivePayload(
                1,
                now,
                archivedExecution,
                initial,
                communications,
                archivedDocuments,
                internalNotes(execution));
        LOG.infof(
                "Built consolidated work-item archive executionId=%s communications=%d documents=%d notes=%d",
                execution.id,
                communications.size(),
                archivedDocuments.size(),
                payload.internalNotes().size());
        return payload;
    }

    @Transactional
    public WorkItemWorkflowView.PickResult pick(
            String token,
            Long executionId,
            boolean force) {
        ApplicationUserEntity actor = authenticated(token);
        WorkItemExecutionEntity execution =
                WorkItemExecutionEntity.findById(executionId);
        if (execution == null) throw new NotFoundException("Work item execution not found");
        if (!execution.tenant.id.equals(actor.tenant.id)) {
            throw new ForbiddenException("Work item is outside your tenant");
        }
        if (execution.currentStatus.terminalStatus) {
            throw new WebApplicationException("Terminal work items cannot be picked", 409);
        }
        if (execution.assignedUser != null && execution.assignedUser.id.equals(actor.id)) {
            return new WorkItemWorkflowView.PickResult(
                    executionView(execution, actor), false, false);
        }
        boolean reassigned = execution.assignedUser != null;
        if (reassigned && !force) {
            throw new WebApplicationException(
                    "This work item is already being worked by "
                            + execution.assignedUser.username,
                    409);
        }
        if (!force && !visibleTo(execution, actor, false)) {
            throw new ForbiddenException(
                    "No assigned workflow activity is available to you");
        }
        String previousUsername =
                execution.assignedUser == null ? null : execution.assignedUser.username;
        execution.assignedUser = actor;
        execution.assignedAt = Instant.now();
        execution.updatedAt = execution.assignedAt;
        LOG.infof(
                "Picked work item executionId=%s actorId=%s reassigned=%s previousAssignee=%s",
                execution.id, actor.id, reassigned, previousUsername);
        return new WorkItemWorkflowView.PickResult(
                executionView(execution, actor), !reassigned, reassigned);
    }

    @Transactional
    public WorkItemWorkflowView.Execution changeExecutionDefinition(
            String token,
            Long executionId,
            Long definitionId) {
        ApplicationUserEntity actor = authenticated(token);
        WorkItemExecutionEntity execution =
                versionedAccessibleExecution(actor, executionId);
        if (execution.currentStatus.terminalStatus) {
            throw new WebApplicationException(
                    "Terminal work items cannot change type", 409);
        }
        if (allowedTransitions(execution, actor).isEmpty()) {
            throw new ForbiddenException(
                    "No assigned workflow activity is available to you");
        }
        ensureOwned(execution, actor);
        WorkItemDefinitionEntity target =
                definitions.requireEffective(definitionId, actor.tenant.id);
        if (execution.definition.id.equals(target.id)) {
            return executionView(execution, actor);
        }
        WorkItemStatusEntity targetStatus = WorkItemStatusEntity.find(
                "definition.id = ?1 and normalizedCode = ?2",
                target.id,
                execution.currentStatus.normalizedCode).firstResult();
        if (targetStatus == null) {
            throw new WebApplicationException(
                    "The selected work-item type does not support the current status",
                    409);
        }
        Long previousDefinitionId = execution.definition.id;
        String previousType = execution.definition.type;
        execution.definition = target;
        execution.currentStatus = targetStatus;
        execution.updatedAt = Instant.now();
        LOG.infof(
                "Changed work-item execution type executionId=%s actorId=%s previousDefinitionId=%s previousType=%s definitionId=%s type=%s status=%s",
                execution.id,
                actor.id,
                previousDefinitionId,
                previousType,
                target.id,
                target.type,
                targetStatus.code);
        return executionView(execution, actor);
    }

    @Transactional
    public WorkItemWorkflowView.InternalNote addInternalNote(
            String token,
            Long executionId,
            String rawContent) {
        ApplicationUserEntity actor = authenticated(token);
        WorkItemExecutionEntity execution =
                versionedAccessibleExecution(actor, executionId);
        requireMutableDetails(execution);
        ensureOwned(execution, actor);
        String content = rawContent == null ? "" : rawContent.trim();
        if (content.isBlank()) throw new BadRequestException("Internal note is required");
        if (content.length() > 10_000) {
            throw new BadRequestException("Internal note must not exceed 10000 characters");
        }
        WorkItemInternalNoteEntity note = new WorkItemInternalNoteEntity();
        note.tenant = execution.tenant;
        note.execution = execution;
        note.author = actor;
        note.content = content;
        note.createdAt = Instant.now();
        Panache.getEntityManager().persist(note);
        execution.updatedAt = note.createdAt;
        LOG.infof("Added internal work-item note executionId=%s noteId=%s actorId=%s",
                executionId, note.id, actor.id);
        return internalNoteView(note);
    }

    @Transactional
    public DocumentDownload document(
            String token,
            Long executionId,
            Long documentId) {
        ApplicationUserEntity actor = authenticated(token);
        WorkItemExecutionEntity execution =
                accessibleExecution(actor, executionId);
        if (execution.dataMigrated) {
            WorkItemArchivePayload.ArchivedDocument document =
                    archiveCodec.read(
                                    execution.tenant.id,
                                    execution.archiveStorageKey)
                            .documents()
                            .stream()
                            .filter(candidate ->
                                    candidate.metadata().id().equals(documentId))
                            .findFirst()
                            .orElseThrow(() -> new NotFoundException(
                                    "Work item document not found"));
            LOG.infof(
                    "Downloaded archived work-item document executionId=%s documentId=%s actorId=%s",
                    executionId, documentId, actor.id);
            return new DocumentDownload(
                    document.metadata().filename(),
                    document.metadata().contentType(),
                    document.content());
        }
        WorkItemDocumentEntity document = WorkItemDocumentEntity.find(
                "id = ?1 and execution.id = ?2 and tenant.id = ?3",
                documentId, execution.id, actor.tenant.id).firstResult();
        if (document == null) throw new NotFoundException("Work item document not found");
        LOG.infof("Downloaded work-item document executionId=%s documentId=%s actorId=%s",
                executionId, documentId, actor.id);
        byte[] content = document.contentData != null
                ? document.contentData
                : attachmentStorage.get(actor.tenant.id, document.storageKey);
        return new DocumentDownload(
                document.filename,
                document.contentType,
                content);
    }

    @Transactional
    public WorkItemWorkflowView.Document uploadInternalDocument(
            String token,
            Long executionId,
            String rawFilename,
            String rawContentType,
            byte[] content) {
        ApplicationUserEntity actor = authenticated(token);
        WorkItemExecutionEntity execution =
                versionedAccessibleExecution(actor, executionId);
        requireMutableDetails(execution);
        ensureOwned(execution, actor);
        String filename = safeFilename(rawFilename);
        if (content == null || content.length == 0) {
            throw new BadRequestException("Document content is required");
        }
        if (content.length > maxAttachmentBytes) {
            throw new BadRequestException("Document exceeds the configured maximum size");
        }
        String contentType = rawContentType == null || rawContentType.isBlank()
                ? "application/octet-stream"
                : rawContentType.trim();
        AttachmentStorage.StoredObject stored = attachmentStorage.put(
                execution.tenant.id,
                "internal",
                filename,
                contentType,
                content);
        WorkItemDocumentEntity document = new WorkItemDocumentEntity();
        document.tenant = execution.tenant;
        document.execution = execution;
        document.filename = filename;
        document.contentType = contentType;
        document.contentSize = stored.size();
        document.origin = DocumentOrigin.INTERNAL;
        document.uploadedBy = actor;
        document.storageProvider = stored.provider();
        document.storageKey = stored.key();
        document.createdAt = Instant.now();
        Panache.getEntityManager().persist(document);
        execution.updatedAt = document.createdAt;
        LOG.infof("Uploaded internal work-item document executionId=%s documentId=%s actorId=%s size=%d storage=%s",
                executionId, document.id, actor.id, stored.size(), stored.provider());
        return documentView(document);
    }

    @Transactional
    public ReplyTarget replyTarget(String token, Long executionId) {
        ApplicationUserEntity actor = authenticated(token);
        WorkItemExecutionEntity execution =
                versionedAccessibleExecution(actor, executionId);
        requireMutableDetails(execution);
        ensureOwned(execution, actor);
        if (execution.initialCommunicationId == null) {
            throw new BadRequestException("This work item is not linked to an email conversation");
        }
        Panache.getEntityManager().lock(
                execution, LockModeType.OPTIMISTIC);
        return new ReplyTarget(
                execution.id,
                execution.tenant.id,
                execution.workAccountId,
                execution.initialCommunicationId);
    }

    @Transactional
    public WorkItemWorkflowView.Execution perform(String token, Long executionId, Long transitionId) {
        ApplicationUserEntity actor = authenticated(token);
        WorkItemExecutionEntity execution =
                versionedAccessibleExecution(actor, executionId);
        ensureOwned(execution, actor);
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
        Set<Long> assigned = new HashSet<>(WorkItemTransitionAssignmentEntity.<WorkItemTransitionAssignmentEntity>list(
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
        return execution.assignedUser != null
                && execution.assignedUser.id.equals(actor.id)
                || WorkItemActivityEntity.count(
                "execution.id = ?1 and performedBy.id = ?2", execution.id, actor.id) > 0
                || WorkItemStatusAssignmentEntity.count(
                "tenant.id = ?1 and status.id = ?2 and user.id = ?3",
                execution.tenant.id, execution.currentStatus.id, actor.id) > 0;
    }

    private boolean visibleInQueue(
            WorkItemExecutionEntity execution,
            ApplicationUserEntity actor,
            boolean includeTerminal,
            String rawScope) {
        if (!visibleTo(execution, actor, includeTerminal)) {
            return false;
        }
        String scope = rawScope == null
                ? "ALL"
                : rawScope.trim().toUpperCase(Locale.ROOT);
        boolean assignedToActor = execution.assignedUser != null
                && execution.assignedUser.id.equals(actor.id);
        return switch (scope) {
            case "MY" -> assignedToActor;
            case "OTHER" -> !assignedToActor;
            case "ALL", "" -> true;
            default -> throw new BadRequestException(
                    "queueScope must be MY, OTHER, or ALL");
        };
    }

    private WorkItemWorkflowView.Execution executionView(WorkItemExecutionEntity execution, ApplicationUserEntity actor) {
        boolean assignedToActor = execution.assignedUser != null
                && execution.assignedUser.id.equals(actor.id);
        List<WorkItemWorkflowView.Transition> transitions =
                (execution.assignedUser == null || assignedToActor
                        ? allowedTransitions(execution, actor)
                        : List.<WorkItemTransitionEntity>of()).stream()
                .map(t -> new WorkItemWorkflowView.Transition(t.id, t.label, t.fromStatus.code, t.toStatus.code)).toList();
        return new WorkItemWorkflowView.Execution(
                execution.id,
                execution.workItemNumber,
                execution.workAccountId,
                execution.initialCommunicationId,
                execution.workAccountEmail,
                execution.emailSubject,
                execution.emailSender,
                execution.definition.id, execution.definition.type, execution.definition.displayName,
                execution.currentStatus.id, execution.currentStatus.code, execution.currentStatus.displayName,
                execution.currentStatus.terminalStatus,
                execution.dataMigrated,
                execution.assignedUser == null ? null : execution.assignedUser.id,
                execution.assignedUser == null ? null : execution.assignedUser.username,
                assignedToActor,
                transitions, activities(execution), execution.updatedAt);
    }

    private List<WorkItemWorkflowView.Activity> activities(
            WorkItemExecutionEntity execution) {
        if (execution.dataMigrated) return List.of();
        return WorkItemActivityEntity.<WorkItemActivityEntity>list(
                        "execution.id = ?1 order by performedAt desc",
                        execution.id)
                .stream()
                .map(activity -> new WorkItemWorkflowView.Activity(
                        activity.transitionLabel,
                        activity.fromStatusCode,
                        activity.toStatusCode,
                        activity.performedBy.id,
                        activity.performedBy.username,
                        activity.performedAt))
                .toList();
    }

    private WorkItemWorkflowView.Detail archivedDetail(
            WorkItemExecutionEntity execution,
            ApplicationUserEntity actor) {
        WorkItemArchivePayload payload = archiveCodec.read(
                execution.tenant.id,
                execution.archiveStorageKey);
        WorkItemArchivePayload.ArchivedExecution archived =
                payload.execution();
        boolean assignedToActor = archived.assignedUserId() != null
                && archived.assignedUserId().equals(actor.id);
        WorkItemWorkflowView.Execution archivedView =
                new WorkItemWorkflowView.Execution(
                        archived.id(),
                        archived.workItemNumber(),
                        archived.workAccountId(),
                        archived.conversationId(),
                        archived.emailId(),
                        archived.emailSubject(),
                        archived.emailSender(),
                        archived.definitionId(),
                        archived.workItemType(),
                        archived.workItemDisplayName(),
                        archived.currentStatusId(),
                        archived.currentStatus(),
                        archived.currentStatusDisplayName(),
                        archived.terminal(),
                        true,
                        archived.assignedUserId(),
                        archived.assignedUsername(),
                        assignedToActor,
                        List.of(),
                        archived.activities(),
                        archived.updatedAt());
        return new WorkItemWorkflowView.Detail(
                archivedView,
                payload.conversation(),
                payload.communications(),
                payload.documents().stream()
                        .map(WorkItemArchivePayload.ArchivedDocument::metadata)
                        .toList(),
                payload.internalNotes(),
                true);
    }

    private static void requireMutableDetails(
            WorkItemExecutionEntity execution) {
        if (execution.dataMigrated) {
            throw new WebApplicationException(
                    "Archived work-item details are read-only", 409);
        }
        if (execution.currentStatus.terminalStatus) {
            throw new WebApplicationException(
                    "Terminal work-item details are read-only", 409);
        }
    }

    private void ensureOwned(
            WorkItemExecutionEntity execution,
            ApplicationUserEntity actor) {
        if (execution.assignedUser == null) {
            execution.assignedUser = actor;
            execution.assignedAt = Instant.now();
            execution.updatedAt = execution.assignedAt;
            LOG.infof("Auto-assigned work item executionId=%s actorId=%s",
                    execution.id, actor.id);
            return;
        }
        if (!execution.assignedUser.id.equals(actor.id)) {
            throw new ForbiddenException(
                    "This work item is being worked by "
                            + execution.assignedUser.username
                            + "; open it in read-only mode or reassign it first");
        }
    }

    private static void appendQueueScope(
            StringBuilder query,
            Map<String, Object> parameters,
            String rawScope,
            Long actorId) {
        String scope = rawScope == null
                ? "ALL"
                : rawScope.trim().toUpperCase(Locale.ROOT);
        switch (scope) {
            case "MY" -> {
                query.append(" and assignedUser.id = :actorId");
                parameters.put("actorId", actorId);
            }
            case "OTHER" -> {
                query.append(" and (assignedUser is null or assignedUser.id <> :actorId)");
                parameters.put("actorId", actorId);
            }
            case "ALL", "" -> {
            }
            default -> throw new BadRequestException(
                    "queueScope must be MY, OTHER, or ALL");
        }
    }

    private List<WorkItemWorkflowView.Document> documents(
            WorkItemExecutionEntity execution) {
        return WorkItemDocumentEntity.<WorkItemDocumentEntity>list(
                        "execution.id = ?1 order by createdAt, filename", execution.id)
                .stream()
                .map(this::documentView)
                .toList();
    }

    private WorkItemWorkflowView.Document documentView(
            WorkItemDocumentEntity document) {
        return new WorkItemWorkflowView.Document(
                document.id,
                document.filename,
                document.contentType,
                document.contentSize,
                document.origin == null ? DocumentOrigin.INBOUND.name() : document.origin.name(),
                document.communication == null
                        ? document.sourceConversationId
                        : document.communication.id,
                document.uploadedBy == null ? null : document.uploadedBy.username,
                document.createdAt);
    }

    private List<WorkItemWorkflowView.Conversation> communications(
            WorkItemExecutionEntity execution) {
        return WorkItemCommunicationEntity
                .<WorkItemCommunicationEntity>list(
                        "execution.id = ?1 order by coalesce(sentAt, createdAt), createdAt, id",
                        execution.id)
                .stream()
                .map(this::communicationView)
                .toList();
    }

    private WorkItemWorkflowView.Conversation communicationView(
            WorkItemCommunicationEntity communication) {
        Instant now = Instant.now();
        boolean hasCachedContent = communication.cachedContentHtml != null
                || communication.cachedContentText != null
                || communication.cachedSnippet != null;
        if (providerReadEnabled) {
            Optional<WorkItemEmailContentResolver> resolver =
                    emailContentResolvers.stream().findFirst();
            if (resolver.isPresent()) {
                try {
                    WorkItemEmailContentResolver.ResolvedContent content =
                            resolver.get().resolve(
                                    new WorkItemEmailContentResolver.EmailReference(
                                            communication.tenant.id,
                                            communication.workAccountId,
                                            communication.providerCode,
                                            communication.providerMessageId));
                    communication.subject = content.subject();
                    communication.sender = content.sender();
                    communication.recipients = content.recipients();
                    communication.sentAt = content.sentAt();
                    communication.cachedSnippet = content.snippet();
                    communication.cachedContentText = content.contentText();
                    communication.cachedContentHtml = content.contentHtml();
                    communication.cacheRefreshedAt = now;
                    communication.cacheExpiresAt =
                            now.plusSeconds(Math.max(0, emailCacheSeconds));
                    return communicationView(
                            communication, content.contentSource(), false);
                } catch (RuntimeException failure) {
                    LOG.warnf(
                            "Conversation and provider email resolution failed; using work-item cache executionId=%s communicationId=%s provider=%s error=%s",
                            communication.execution.id, communication.id,
                            communication.providerCode, failure.getMessage());
                }
            }
        }
        if (!providerReadEnabled
                && hasCachedContent
                && communication.cacheExpiresAt != null
                && communication.cacheExpiresAt.isAfter(now)) {
            return communicationView(communication, "CACHE", false);
        }
        return communicationView(
                communication,
                hasCachedContent ? "FALLBACK_CACHE" : "METADATA_ONLY",
                hasCachedContent);
    }

    private WorkItemWorkflowView.Conversation communicationView(
            WorkItemCommunicationEntity communication,
            String contentSource,
            boolean staleFallback) {
        return new WorkItemWorkflowView.Conversation(
                communication.id,
                communication.subject,
                communication.sender,
                communication.recipients,
                communication.sentAt,
                communication.cachedSnippet,
                communication.cachedContentText,
                communication.cachedContentHtml,
                communication.direction,
                contentSource,
                staleFallback);
    }

    private List<WorkItemWorkflowView.InternalNote> internalNotes(
            WorkItemExecutionEntity execution) {
        return WorkItemInternalNoteEntity.<WorkItemInternalNoteEntity>list(
                        "execution.id = ?1 order by createdAt", execution.id)
                .stream()
                .map(this::internalNoteView)
                .toList();
    }

    private WorkItemWorkflowView.InternalNote internalNoteView(
            WorkItemInternalNoteEntity note) {
        return new WorkItemWorkflowView.InternalNote(
                note.id,
                note.author.id,
                note.author.username,
                note.content,
                note.createdAt);
    }

    private WorkItemExecutionEntity accessibleExecution(
            ApplicationUserEntity actor,
            Long executionId) {
        WorkItemExecutionEntity execution = WorkItemExecutionEntity.findById(executionId);
        if (execution == null) throw new NotFoundException("Work item execution not found");
        if (!execution.tenant.id.equals(actor.tenant.id)) {
            throw new ForbiddenException("Work item is outside your tenant");
        }
        if (execution.assignedUser == null
                && !visibleTo(execution, actor, true)) {
            throw new ForbiddenException("Work item is not assigned to you");
        }
        return execution;
    }

    private WorkItemExecutionEntity versionedAccessibleExecution(
            ApplicationUserEntity actor,
            Long executionId) {
        WorkItemExecutionEntity execution =
                WorkItemExecutionEntity.findById(executionId);
        if (execution == null) {
            throw new NotFoundException("Work item execution not found");
        }
        if (!execution.tenant.id.equals(actor.tenant.id)) {
            throw new ForbiddenException("Work item is outside your tenant");
        }
        if (execution.assignedUser == null
                && !visibleTo(execution, actor, true)) {
            throw new ForbiddenException("Work item is not assigned to you");
        }
        return execution;
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

    private static String safeFilename(String value) {
        String filename = value == null ? "" : value.replace("\\", "/");
        filename = filename.substring(filename.lastIndexOf('/') + 1)
                .replace("\r", "")
                .replace("\n", "")
                .trim();
        if (filename.isBlank()) throw new BadRequestException("Document filename is required");
        if (filename.length() > 2048) {
            throw new BadRequestException("Document filename is too long");
        }
        return filename;
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
    private TenantEntity targetTenant(ApplicationUserEntity actor, Long requestedTenantId) {
        Long tenantId = actor.role == UserRole.GLOBAL_ADMIN ? requestedTenantId : actor.tenant.id;
        if (actor.role != UserRole.GLOBAL_ADMIN && requestedTenantId != null && !requestedTenantId.equals(actor.tenant.id)) {
            throw new ForbiddenException("Tenant is outside your scope");
        }
        if (tenantId == null) throw new BadRequestException("tenantId is required for GLOBAL_ADMIN");
        TenantEntity tenant = TenantEntity.findById(tenantId);
        if (tenant == null || !tenant.active) throw new NotFoundException("Active tenant not found");
        return tenant;
    }
    private void assertTenant(ApplicationUserEntity actor, Long tenantId) {
        if (actor.role != UserRole.GLOBAL_ADMIN && !actor.tenant.id.equals(tenantId)) {
            throw new ForbiddenException("Assignment is outside your tenant");
        }
    }
    private static void requireStatusDefinition(WorkItemStatusEntity status, Long definitionId) {
        if (status == null || !status.definition.id.equals(definitionId)) throw new BadRequestException("Status does not belong to the work item");
    }
    private static void requireTransitionDefinition(WorkItemTransitionEntity transition, Long definitionId) {
        if (transition == null || !transition.definition.id.equals(definitionId)) throw new BadRequestException("Transition does not belong to the work item");
    }

    public record AssignmentInput(Long tenantId, Long definitionId, Long statusId, Long transitionId, Long userId) {}
    public record DocumentDownload(String filename, String contentType, byte[] content) {}
    public record ReplyTarget(
            Long executionId,
            Long tenantId,
            Long workAccountId,
            Long conversationId) {}
}
