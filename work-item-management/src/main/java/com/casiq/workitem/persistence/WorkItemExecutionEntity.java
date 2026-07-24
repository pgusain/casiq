package com.casiq.workitem.persistence;

import com.casiq.usermanagement.persistence.TenantEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "work_item_execution",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_work_item_execution_tenant_number",
                columnNames = {"tenant_id", "work_item_number"}))
public class WorkItemExecutionEntity extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @Column(name = "work_item_number", nullable = false, updatable = false)
    public Long workItemNumber;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id") public TenantEntity tenant;
    @Column(name = "work_account_id", nullable = false) public UUID workAccountId;
    @Column(name = "work_account_email", nullable = false, length = 320) public String workAccountEmail;
    @Column(name = "work_account_normalized_email", nullable = false, length = 320)
    public String workAccountNormalizedEmail;
    @Column(name = "conversation_id", unique = true) public UUID conversationId;
    @Column(name = "initial_communication_id") public UUID initialCommunicationId;
    @Column(name = "email_subject", length = 998) public String emailSubject;
    @Column(name = "email_sender", length = 998) public String emailSender;
    @Column(name = "email_recipients", columnDefinition = "TEXT") public String emailRecipients;
    @Column(name = "email_sent_at") public Instant emailSentAt;
    @Column(name = "email_content_html", columnDefinition = "TEXT") public String emailContentHtml;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "definition_id") public WorkItemDefinitionEntity definition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "current_status_id") public WorkItemStatusEntity currentStatus;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
