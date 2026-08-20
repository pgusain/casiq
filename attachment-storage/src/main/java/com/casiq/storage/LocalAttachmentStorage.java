package com.casiq.storage;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

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
            Long tenantId,
            String category,
            String filename,
            String contentType,
            byte[] content) {
        if (content == null) throw new IllegalArgumentException("Attachment content is required");
        String safeCategory = category == null
                ? "other"
                : category.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        String key = safeCategory + "/" + UUID.randomUUID();
        return putAtKey(tenantId, key, contentType, content);
    }

    @Override
    public StoredObject putAtKey(
            Long tenantId,
            String key,
            String contentType,
            byte[] content) {
        MDC.put("tenantCode", String.valueOf(tenantId));
        try {
            if (key == null || key.isBlank() || key.startsWith("/")
                    || key.contains("..") || key.contains("\\")) {
                throw new IllegalArgumentException("Invalid local attachment key");
            }
            if (content == null) throw new IllegalArgumentException("Attachment content is required");
            String storedKey = tenantId + "/" + key;
            Path target = resolve(tenantId, storedKey);
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
                    (Object) tenantId, storedKey, content.length);
            return new StoredObject("LOCAL", storedKey, content.length);
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error storing local attachment tenantId=%s key=%s", tenantId, key);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    @Override
    public byte[] get(Long tenantId, String key) {
        MDC.put("tenantCode", String.valueOf(tenantId));
        try {
            Path target = resolve(tenantId, key);
            try {
                return Files.readAllBytes(target);
            } catch (IOException failure) {
                throw new IllegalStateException("Unable to read attachment from local disk", failure);
            }
        } catch (RuntimeException | Error e) {
            LOG.errorf("Error reading local attachment tenantId=%s key=%s", tenantId, key);
            throw e;
        } finally {
            MDC.remove("tenantCode");
        }
    }

    private Path resolve(Long tenantId, String key) {
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
