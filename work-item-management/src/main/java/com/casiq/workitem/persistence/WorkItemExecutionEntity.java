package com.casiq.workitem.persistence;

import com.casiq.usermanagement.persistence.TenantEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_item_execution")
public class WorkItemExecutionEntity extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id") public TenantEntity tenant;
    @Column(name = "work_account_id", nullable = false) public UUID workAccountId;
    @Column(name = "work_account_email", nullable = false, length = 320) public String workAccountEmail;
    @Column(name = "work_account_normalized_email", nullable = false, length = 320)
    public String workAccountNormalizedEmail;
    @Column(name = "conversation_id", unique = true) public UUID conversationId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "definition_id") public WorkItemDefinitionEntity definition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "current_status_id") public WorkItemStatusEntity currentStatus;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
