package com.casiq.workitem.persistence;

import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import com.casiq.usermanagement.persistence.TenantEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "work_item_activity")
public class WorkItemActivityEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id") public TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "execution_id") public WorkItemExecutionEntity execution;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "transition_id") public WorkItemTransitionEntity transition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "performed_by_user_id") public ApplicationUserEntity performedBy;
    @Column(name = "transition_label", nullable = false, length = 160) public String transitionLabel;
    @Column(name = "from_status_code", nullable = false, length = 64) public String fromStatusCode;
    @Column(name = "to_status_code", nullable = false, length = 64) public String toStatusCode;
    @Column(name = "performed_at", nullable = false) public Instant performedAt;
}
