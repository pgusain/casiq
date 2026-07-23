package com.casiq.workitem.api;

import com.casiq.usermanagement.api.AuthResource;
import com.casiq.workitem.service.WorkItemDefinitionService;
import jakarta.inject.Inject;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

@Path("/api/v1/work-items")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WorkItemDefinitionResource {
    @Inject WorkItemDefinitionService workItems;

    @GET @Path("/definitions")
    public List<WorkItemDefinitionView> definitions(@CookieParam(AuthResource.SESSION_COOKIE) String token) {
        return workItems.listDefinitions(token);
    }
    @GET @Path("/effective")
    public List<WorkItemDefinitionView> effective(@CookieParam(AuthResource.SESSION_COOKIE) String token,
                                                  @QueryParam("tenantId") UUID tenantId) {
        return workItems.effective(token, tenantId);
    }
    @POST @Path("/definitions")
    public WorkItemDefinitionView create(@CookieParam(AuthResource.SESSION_COOKIE) String token,
                                         @NotNull WorkItemDefinitionService.DefinitionInput input) {
        return workItems.create(token, input);
    }
    @PUT @Path("/definitions/{id}")
    public WorkItemDefinitionView update(@CookieParam(AuthResource.SESSION_COOKIE) String token,
                                         @PathParam("id") UUID id,
                                         @NotNull WorkItemDefinitionService.DefinitionInput input) {
        return workItems.update(token, id, input);
    }
}
