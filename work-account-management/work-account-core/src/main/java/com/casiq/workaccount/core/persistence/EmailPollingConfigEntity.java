package com.casiq.workaccount.core.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "email_polling_config")
public class EmailPollingConfigEntity extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) public Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "work_account_id", unique = true)
    public WorkAccountEntity workAccount;
    @Column(name = "email_id", nullable = false, length = 320) public String emailId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "provider_id")
    public EmailProviderEntity provider;
    @Column(name = "access_token", columnDefinition = "TEXT") public String accessToken;
    @Column(name = "access_token_expires_at") public Instant accessTokenExpiresAt;
    @Column(name = "next_refresh_at") public Instant nextRefreshAt;
    @Column(name = "last_polled_at") public Instant lastPolledAt;
    @Column(name = "locked_until") public Instant lockedUntil;
    @Column(name = "lock_owner", length = 64) public String lockOwner;
    @Column(name = "last_error", length = 1000) public String lastError;
    @Column(name = "consecutive_failures", nullable = false) public int consecutiveFailures;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
}
