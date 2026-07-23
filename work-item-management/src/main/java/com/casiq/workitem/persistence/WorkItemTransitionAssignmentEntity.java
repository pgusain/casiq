package com.casiq.workitem.persistence;

import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import com.casiq.usermanagement.persistence.TenantEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_item_transition_assignment")
public class WorkItemTransitionAssignmentEntity extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id") public TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "definition_id") public WorkItemDefinitionEntity definition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "transition_id") public WorkItemTransitionEntity transition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id") public ApplicationUserEntity user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_user_id") public ApplicationUserEntity createdBy;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
