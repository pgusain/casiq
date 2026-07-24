package com.casiq.workaccount.core.persistence;

import com.casiq.usermanagement.persistence.TenantEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_account_conversation_attachment", uniqueConstraints = @UniqueConstraint(
        name = "uq_conversation_provider_attachment",
        columnNames = {"conversation_id", "provider_attachment_id"}))
public class WorkAccountConversationAttachmentEntity extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id")
    public TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "conversation_id")
    public WorkAccountConversationEntity conversation;
    @Column(name = "provider_attachment_id", nullable = false, length = 255)
    public String providerAttachmentId;
    @Column(nullable = false, length = 2048) public String filename;
    @Column(name = "content_type", length = 512) public String contentType;
    @Column(name = "content_size", nullable = false) public long contentSize;
    @Column(name = "content_data", columnDefinition = "BYTEA")
    public byte[] contentData;
    @Column(name = "storage_provider", length = 16) public String storageProvider;
    @Column(name = "storage_key", columnDefinition = "TEXT") public String storageKey;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
