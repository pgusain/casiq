package com.casiq.workitem.persistence;

import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_item_document", uniqueConstraints = @UniqueConstraint(
        name = "uq_work_item_source_attachment",
        columnNames = {"execution_id", "source_attachment_id"}))
public class WorkItemDocumentEntity extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id")
    public TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "execution_id")
    public WorkItemExecutionEntity execution;
    @Column(name = "source_attachment_id") public UUID sourceAttachmentId;
    @Column(nullable = false, length = 2048) public String filename;
    @Column(name = "content_type", length = 512) public String contentType;
    @Column(name = "content_size", nullable = false) public long contentSize;
    @Column(name = "content_data", columnDefinition = "BYTEA")
    public byte[] contentData;
    @Enumerated(EnumType.STRING)
    @Column(name = "document_origin", nullable = false, length = 16)
    public DocumentOrigin origin;
    @Column(name = "source_conversation_id") public UUID sourceConversationId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "communication_id")
    public WorkItemCommunicationEntity communication;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "uploaded_by_user_id")
    public ApplicationUserEntity uploadedBy;
    @Column(name = "storage_provider", length = 16) public String storageProvider;
    @Column(name = "storage_key", columnDefinition = "TEXT") public String storageKey;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
