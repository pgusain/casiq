package com.casiq.workitem.persistence;

import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "work_item_execution",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_work_item_execution_tenant_number",
                columnNames = {"tenant_id", "work_item_number"}))
public class WorkItemExecutionEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) public Long id;
    @Version
    @Column(name = "version", nullable = false)
    public long version;
    @Column(name = "work_item_number", nullable = false, updatable = false)
    public Long workItemNumber;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id") public TenantEntity tenant;
    @Column(name = "work_account_id", nullable = false) public Long workAccountId;
    @Column(name = "work_account_email", nullable = false, length = 320) public String workAccountEmail;
    @Column(name = "work_account_normalized_email", nullable = false, length = 320)
    public String workAccountNormalizedEmail;
    @Column(name = "conversation_id", unique = true) public Long conversationId;
    @Column(name = "initial_communication_id") public Long initialCommunicationId;
    @Column(name = "email_subject", length = 998) public String emailSubject;
    @Column(name = "email_sender", length = 998) public String emailSender;
    @Column(name = "email_recipients", columnDefinition = "TEXT") public String emailRecipients;
    @Column(name = "email_sent_at") public Instant emailSentAt;
    @Column(name = "email_content_html", columnDefinition = "TEXT") public String emailContentHtml;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "definition_id") public WorkItemDefinitionEntity definition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "current_status_id") public WorkItemStatusEntity currentStatus;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "assigned_user_id")
    public ApplicationUserEntity assignedUser;
    @Column(name = "assigned_at") public Instant assignedAt;
    @Column(name = "data_migrated", nullable = false)
    public boolean dataMigrated;
    @Column(name = "archive_storage_provider", length = 16)
    public String archiveStorageProvider;
    @Column(name = "archive_storage_key", columnDefinition = "TEXT")
    public String archiveStorageKey;
    @Column(name = "archived_at") public Instant archivedAt;
    @Column(name = "archive_next_attempt_at", nullable = false)
    public Instant archiveNextAttemptAt;
    @Column(name = "archive_locked_until") public Instant archiveLockedUntil;
    @Column(name = "archive_lock_owner", length = 64)
    public String archiveLockOwner;
    @Column(name = "archive_last_error", length = 1000)
    public String archiveLastError;
    @Column(name = "archive_failures", nullable = false)
    public int archiveFailures;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
