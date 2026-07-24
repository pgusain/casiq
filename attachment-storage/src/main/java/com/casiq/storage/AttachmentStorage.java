package com.casiq.storage;

import java.util.UUID;

public interface AttachmentStorage {
    StoredObject put(
            UUID tenantId,
            String category,
            String filename,
            String contentType,
            byte[] content);

    byte[] get(UUID tenantId, String key);

    record StoredObject(String provider, String key, long size) {}
}
