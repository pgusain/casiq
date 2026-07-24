package com.casiq.storage;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
@IfBuildProperty(name = "casiq.attachment-storage.provider", stringValue = "local", enableIfMissing = true)
public class LocalAttachmentStorage implements AttachmentStorage {
    private static final Logger LOG = Logger.getLogger(LocalAttachmentStorage.class);

    @ConfigProperty(name = "casiq.attachment-storage.local-root")
    String configuredRoot;

    @Override
    public StoredObject put(
            UUID tenantId,
            String category,
            String filename,
            String contentType,
            byte[] content) {
        if (content == null) throw new IllegalArgumentException("Attachment content is required");
        String safeCategory = category == null
                ? "other"
                : category.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        String key = tenantId + "/" + safeCategory + "/" + UUID.randomUUID();
        Path target = resolve(tenantId, key);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(temporary, content);
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to store attachment on local disk", failure);
        }
        LOG.debugf("Stored local attachment tenantId=%s key=%s size=%d",
                tenantId, key, content.length);
        return new StoredObject("LOCAL", key, content.length);
    }

    @Override
    public byte[] get(UUID tenantId, String key) {
        Path target = resolve(tenantId, key);
        try {
            return Files.readAllBytes(target);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read attachment from local disk", failure);
        }
    }

    private Path resolve(UUID tenantId, String key) {
        String requiredPrefix = tenantId + "/";
        if (key == null || !key.startsWith(requiredPrefix)) {
            throw new IllegalArgumentException("Attachment key is outside the tenant");
        }
        Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root.resolve(tenantId.toString()).normalize())) {
            throw new IllegalArgumentException("Attachment key is outside the tenant");
        }
        return target;
    }
}
