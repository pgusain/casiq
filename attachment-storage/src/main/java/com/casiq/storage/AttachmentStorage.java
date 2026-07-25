package com.casiq.storage;

public interface AttachmentStorage {
    StoredObject put(
            Long tenantId,
            String category,
            String filename,
            String contentType,
            byte[] content);

    StoredObject putAtKey(
            Long tenantId,
            String key,
            String contentType,
            byte[] content);

    byte[] get(Long tenantId, String key);

    record StoredObject(String provider, String key, long size) {}
}
