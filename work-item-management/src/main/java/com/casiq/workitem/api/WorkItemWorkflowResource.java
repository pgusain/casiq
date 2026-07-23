package com.casiq.workitem.api;

import com.casiq.usermanagement.api.AuthResource;
import com.casiq.workitem.service.WorkItemWorkflowService;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

@Path("/api/v1/work-items")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WorkItemWorkflowResource {
    @Inject WorkItemWorkflowService workflows;

    @GET @Path("/assignments")
    public List<WorkItemWorkflowView.Assignment> assignments(
            @CookieParam(AuthResource.SESSION_COOKIE) String token, @QueryParam("tenantId") UUID tenantId) {
        return workflows.listAssignments(token, tenantId);
    }
    @POST @Path("/assignments")
    public WorkItemWorkflowView.Assignment assign(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @NotNull WorkItemWorkflowService.AssignmentInput input) {
        return workflows.assign(token, input);
    }
    @DELETE @Path("/assignments/{type}/{id}")
    public Response remove(@CookieParam(AuthResource.SESSION_COOKIE) String token,
                           @PathParam("type") String type, @PathParam("id") UUID id) {
        workflows.removeAssignment(token, type, id);
        return Response.noContent().build();
    }
    @GET @Path("/my-work")
    public WorkItemWorkflowView.WorkPage myWork(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @QueryParam("workItemType") String workItemType,
            @QueryParam("status") String status,
            @QueryParam("email") String email,
            @DefaultValue("false") @QueryParam("includeTerminal") boolean includeTerminal,
            @DefaultValue("0") @QueryParam("page") int page,
            @DefaultValue("20") @QueryParam("size") int size,
            @DefaultValue("updatedAt") @QueryParam("sortBy") String sortBy,
            @DefaultValue("desc") @QueryParam("sortDirection") String sortDirection) {
        return workflows.myWork(
                token, workItemType, status, email, includeTerminal,
                page, size, sortBy, sortDirection);
    }
    @GET @Path("/executions/{executionId}")
    public WorkItemWorkflowView.Detail detail(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("executionId") UUID executionId) {
        return workflows.detail(token, executionId);
    }
    @POST @Path("/executions/{executionId}/transitions/{transitionId}")
    @Consumes(MediaType.WILDCARD)
    public WorkItemWorkflowView.Execution perform(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("executionId") UUID executionId, @PathParam("transitionId") UUID transitionId) {
        return workflows.perform(token, executionId, transitionId);
    }
}
