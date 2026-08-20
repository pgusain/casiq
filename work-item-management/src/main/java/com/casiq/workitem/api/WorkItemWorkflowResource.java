package com.casiq.workitem.api;

import com.casiq.usermanagement.api.AuthResource;
import com.casiq.workitem.service.WorkItemWorkflowService;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

@Path("/api/v1/work-items")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WorkItemWorkflowResource {
    private static final Logger LOG = Logger.getLogger(WorkItemWorkflowResource.class);
    @Inject WorkItemWorkflowService workflows;

    @GET @Path("/assignments")
    public List<WorkItemWorkflowView.Assignment> assignments(
            @CookieParam(AuthResource.SESSION_COOKIE) String token, @QueryParam("tenantId") Long tenantId) {
        MDC.put("tenantCode", String.valueOf(tenantId));
        try {
            LOG.debugf("Entering assignments tenantId=%s", tenantId);
            return workflows.listAssignments(token, tenantId);
        } finally {
            MDC.remove("tenantCode");
        }
    }
    @POST @Path("/assignments")
    public WorkItemWorkflowView.Assignment assign(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @NotNull WorkItemWorkflowService.AssignmentInput input) {
        return workflows.assign(token, input);
    }
    @DELETE @Path("/assignments/{type}/{id}")
    public Response remove(@CookieParam(AuthResource.SESSION_COOKIE) String token,
                           @PathParam("type") String type, @PathParam("id") Long id) {
        workflows.removeAssignment(token, type, id);
        return Response.noContent().build();
    }
    @GET @Path("/my-work")
    public WorkItemWorkflowView.WorkPage myWork(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @DefaultValue("ALL") @QueryParam("queueScope") String queueScope,
            @QueryParam("workItemType") String workItemType,
            @QueryParam("status") String status,
            @QueryParam("email") String email,
            @DefaultValue("false") @QueryParam("includeTerminal") boolean includeTerminal,
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size,
            @DefaultValue("updatedAt") @QueryParam("sortBy") String sortBy,
            @DefaultValue("desc") @QueryParam("sortDirection") String sortDirection) {
        return workflows.myWork(
                token, queueScope, workItemType, status, email, includeTerminal,
                page, size, sortBy, sortDirection);
    }
    @GET @Path("/my-work/status-summary")
    public List<WorkItemWorkflowView.StatusCount> myWorkStatusSummary(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @DefaultValue("ALL") @QueryParam("queueScope") String queueScope,
            @QueryParam("workItemType") String workItemType,
            @QueryParam("email") String email,
            @DefaultValue("false") @QueryParam("includeTerminal") boolean includeTerminal) {
        return workflows.myWorkStatusSummary(
                token, queueScope, workItemType, email, includeTerminal);
    }
    @POST @Path("/executions/{executionId}/pick")
    @Consumes(MediaType.WILDCARD)
    public WorkItemWorkflowView.PickResult pick(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("executionId") Long executionId,
            @DefaultValue("false") @QueryParam("force") boolean force) {
        return workflows.pick(token, executionId, force);
    }
    @PUT @Path("/executions/{executionId}/type")
    public WorkItemWorkflowView.Execution changeType(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("executionId") Long executionId,
            @NotNull ChangeTypeInput input) {
        return workflows.changeExecutionDefinition(
                token, executionId, input.definitionId());
    }
    @GET @Path("/executions/{executionId}")
    public WorkItemWorkflowView.Detail detail(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("executionId") Long executionId) {
        return workflows.detail(token, executionId);
    }
    @POST @Path("/executions/{executionId}/notes")
    public WorkItemWorkflowView.InternalNote addNote(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("executionId") Long executionId,
            @NotNull NoteInput input) {
        return workflows.addInternalNote(token, executionId, input.content());
    }
    @GET @Path("/executions/{executionId}/documents/{documentId}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response document(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("executionId") Long executionId,
            @PathParam("documentId") Long documentId) {
        WorkItemWorkflowService.DocumentDownload document =
                workflows.document(token, executionId, documentId);
        String contentType = document.contentType() == null
                || document.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM
                : document.contentType();
        String encoded = URLEncoder.encode(document.filename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return Response.ok(document.content(), contentType)
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }
    @POST @Path("/executions/{executionId}/documents")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public WorkItemWorkflowView.Document uploadDocument(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("executionId") Long executionId,
            @RestForm("file") FileUpload file) {
        if (file == null) throw new BadRequestException("Document file is required");
        try {
            return workflows.uploadInternalDocument(
                    token,
                    executionId,
                    file.fileName(),
                    file.contentType(),
                    Files.readAllBytes(file.uploadedFile()));
        } catch (IOException failure) {
            throw new InternalServerErrorException("Unable to read uploaded document", failure);
        }
    }
    @POST @Path("/executions/{executionId}/transitions/{transitionId}")
    @Consumes(MediaType.WILDCARD)
    public WorkItemWorkflowView.Execution perform(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("executionId") Long executionId, @PathParam("transitionId") Long transitionId) {
        return workflows.perform(token, executionId, transitionId);
    }

    public record NoteInput(
            @NotBlank @Size(max = 10_000) String content) {}

    public record ChangeTypeInput(
            @NotNull Long definitionId) {}
}
