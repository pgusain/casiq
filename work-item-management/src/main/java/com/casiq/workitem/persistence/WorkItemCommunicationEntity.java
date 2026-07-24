package com.casiq.workitem.persistence;

import com.casiq.usermanagement.persistence.TenantEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "work_item_communication",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_work_item_communication_provider_message",
                        columnNames = {"execution_id", "provider_message_id"}),
                @UniqueConstraint(
                        name = "uq_work_item_communication_outbound_request",
                        columnNames = "outbound_request_id")
        })
public class WorkItemCommunicationEntity extends PanacheEntityBase {
    @Id public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id") public TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "execution_id") public WorkItemExecutionEntity execution;
    @Column(name = "work_account_id", nullable = false) public UUID workAccountId;
    @Column(name = "provider_code", nullable = false, length = 32) public String providerCode;
    @Column(name = "provider_message_id", nullable = false, length = 255)
    public String providerMessageId;
    @Column(name = "provider_thread_id", length = 255) public String providerThreadId;
    @Column(name = "rfc_message_id", length = 998) public String rfcMessageId;
    @Column(name = "in_reply_to", length = 998) public String inReplyTo;
    @Column(name = "reference_ids", columnDefinition = "TEXT") public String referenceIds;
    @Column(nullable = false, length = 16) public String direction;
    @Column(length = 998) public String subject;
    @Column(length = 998) public String sender;
    @Column(columnDefinition = "TEXT") public String recipients;
    @Column(name = "sent_at") public Instant sentAt;
    @Column(name = "cached_snippet", columnDefinition = "TEXT") public String cachedSnippet;
    @Column(name = "cached_content_text", columnDefinition = "TEXT") public String cachedContentText;
    @Column(name = "cached_content_html", columnDefinition = "TEXT") public String cachedContentHtml;
    @Column(name = "cache_refreshed_at") public Instant cacheRefreshedAt;
    @Column(name = "cache_expires_at") public Instant cacheExpiresAt;
    @Column(name = "outbound_request_id") public UUID outboundRequestId;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
