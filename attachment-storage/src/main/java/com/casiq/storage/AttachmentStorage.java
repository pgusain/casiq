package com.casiq.storage;

import org.jboss.logging.Logger;

public interface AttachmentStorage {
    Logger LOG = Logger.getLogger(AttachmentStorage.class);

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
