package com.casiq.workaccount.core.api;

import com.casiq.usermanagement.api.AuthResource;
import com.casiq.workaccount.core.service.WorkAccountReplyService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.UUID;
import org.jboss.logging.Logger;
import org.slf4j.MDC;
import java.util.List;

@Path("/api/v1/work-item-replies")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WorkAccountReplyResource {
    private static final Logger LOG = Logger.getLogger(WorkAccountReplyResource.class);
    @Inject WorkAccountReplyService replies;

    @POST
    @Path("/{executionId}")
    public WorkAccountReplyService.ReplyView reply(
            @CookieParam(AuthResource.SESSION_COOKIE) String token,
            @PathParam("executionId") Long executionId,
            @Valid @NotNull ReplyInput input) {
        LOG.debugf("Entering reply executionId=%s requestId=%s", executionId, input.requestId());
        return replies.reply(
                token,
                executionId,
                input.requestId(),
                input.htmlBody(),
                input.documentIds());
    }

    public record ReplyInput(
            @NotNull UUID requestId,
            @NotBlank @Size(max = 60_000) String htmlBody,
            List<Long> documentIds) {}
}
