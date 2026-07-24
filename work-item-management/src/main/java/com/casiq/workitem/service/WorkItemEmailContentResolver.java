package com.casiq.workitem.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Email-content boundary implemented by work-account management. Resolution
 * checks the materialized conversation first and uses the provider as fallback.
 */
public interface WorkItemEmailContentResolver {
    ResolvedContent resolve(EmailReference reference);

    record EmailReference(
            UUID tenantId,
            UUID workAccountId,
            String providerCode,
            String providerMessageId) {}

    record ResolvedContent(
            String subject,
            String sender,
            String recipients,
            Instant sentAt,
            String snippet,
            String contentText,
            String contentHtml,
            String contentSource) {}
}
