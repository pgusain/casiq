package com.casiq.usermanagement.api;

import jakarta.validation.ConstraintViolationException;
import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
public class ApiExceptionMapper implements ExceptionMapper<Exception> {
    private static final Logger LOG = Logger.getLogger(ApiExceptionMapper.class);

    @Override
    public Response toResponse(Exception exception) {
        boolean optimisticConflict = isOptimisticConflict(exception);
        int status = optimisticConflict
                ? 409
                : exception instanceof WebApplicationException web
                ? web.getResponse().getStatus()
                : exception instanceof ConstraintViolationException ? 400 : 500;
        if (status == 500) LOG.error("Unhandled user-management API exception", exception);
        String message = optimisticConflict
                ? "This work item was updated by another user. Refresh and retry."
                : status == 500
                ? "An unexpected error occurred"
                : exception.getMessage();
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .entity(new ErrorResponse(message == null ? "Request failed" : message)).build();
    }

    private static boolean isOptimisticConflict(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof OptimisticLockException
                    || "StaleObjectStateException".equals(
                            current.getClass().getSimpleName())
                    || "StaleStateException".equals(
                            current.getClass().getSimpleName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public record ErrorResponse(String error) {}
}
