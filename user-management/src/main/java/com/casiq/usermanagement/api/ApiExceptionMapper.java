package com.casiq.usermanagement.api;

import jakarta.validation.ConstraintViolationException;
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
        int status = exception instanceof WebApplicationException web
                ? web.getResponse().getStatus()
                : exception instanceof ConstraintViolationException ? 400 : 500;
        if (status == 500) LOG.error("Unhandled user-management API exception", exception);
        String message = status == 500 ? "An unexpected error occurred" : exception.getMessage();
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .entity(new ErrorResponse(message == null ? "Request failed" : message)).build();
    }

    public record ErrorResponse(String error) {}
}
