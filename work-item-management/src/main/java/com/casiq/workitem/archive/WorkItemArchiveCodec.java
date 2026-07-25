package com.casiq.workitem.archive;

import com.casiq.storage.AttachmentStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;

@ApplicationScoped
public class WorkItemArchiveCodec {
    private static final String CONTENT_TYPE = "application/json";
    @Inject ObjectMapper objectMapper;
    @Inject AttachmentStorage storage;

    public AttachmentStorage.StoredObject write(
            WorkItemArchivePayload payload) {
        try {
            byte[] content = objectMapper.writeValueAsBytes(payload);
            String key = "work-items/" + payload.execution().id() + ".json";
            return storage.putAtKey(
                    payload.execution().tenantId(),
                    key,
                    CONTENT_TYPE,
                    content);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to serialize work-item archive", failure);
        }
    }

    public WorkItemArchivePayload read(Long tenantId, String key) {
        try {
            return objectMapper.readValue(
                    storage.get(tenantId, key),
                    WorkItemArchivePayload.class);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to deserialize work-item archive", failure);
        }
    }
}
