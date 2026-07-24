package com.casiq.storage;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
@IfBuildProperty(name = "casiq.attachment-storage.provider", stringValue = "s3")
public class S3AttachmentStorage implements AttachmentStorage {
    private static final Logger LOG = Logger.getLogger(S3AttachmentStorage.class);

    @Inject S3Client s3;
    @ConfigProperty(name = "casiq.attachment-storage.s3-bucket-prefix")
    String bucketPrefix;

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
        String key = safeCategory + "/" + UUID.randomUUID();
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket(tenantId))
                .key(key);
        if (contentType != null && !contentType.isBlank()) {
            request.contentType(contentType);
        }
        s3.putObject(request.build(), RequestBody.fromBytes(content));
        LOG.debugf("Stored S3 attachment tenantId=%s bucket=%s key=%s size=%d",
                tenantId, bucket(tenantId), key, content.length);
        return new StoredObject("S3", key, content.length);
    }

    @Override
    public byte[] get(UUID tenantId, String key) {
        if (key == null || key.isBlank() || key.contains("..")) {
            throw new IllegalArgumentException("Invalid S3 attachment key");
        }
        return s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(bucket(tenantId))
                        .key(key)
                        .build())
                .asByteArray();
    }

    private String bucket(UUID tenantId) {
        String prefix = bucketPrefix.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9.-]", "-");
        String bucket = prefix + tenantId;
        if (bucket.length() < 3 || bucket.length() > 63) {
            throw new IllegalStateException("Derived tenant S3 bucket name must contain 3-63 characters");
        }
        return bucket;
    }
}
