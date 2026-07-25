package com.casiq.workitem.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "work_item_status_transition", uniqueConstraints = @UniqueConstraint(
        name = "uq_work_item_transition", columnNames = {"definition_id", "from_status_id", "to_status_id"}))
public class WorkItemTransitionEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) public Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "definition_id") public WorkItemDefinitionEntity definition;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "from_status_id") public WorkItemStatusEntity fromStatus;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "to_status_id") public WorkItemStatusEntity toStatus;
    @Column(nullable = false, length = 160) public String label;
}
