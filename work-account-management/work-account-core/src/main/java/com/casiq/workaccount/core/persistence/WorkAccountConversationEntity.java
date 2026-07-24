package com.casiq.workaccount.core.persistence;

import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.workitem.persistence.WorkItemExecutionEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_account_conversation", uniqueConstraints = @UniqueConstraint(
        name = "uq_work_account_conversation_message",
        columnNames = {"work_account_id", "provider_message_id"}))
public class WorkAccountConversationEntity extends PanacheEntityBase {
    @Id @GeneratedValue public UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "tenant_id") public TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "work_account_id") public WorkAccountEntity workAccount;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "provider_code") public EmailProviderEntity provider;
    @Column(name = "provider_message_id", nullable = false, length = 255) public String providerMessageId;
    @Column(name = "provider_thread_id", length = 255) public String providerThreadId;
    @Column(name = "rfc_message_id", length = 998) public String rfcMessageId;
    @Column(name = "in_reply_to", length = 998) public String inReplyTo;
    @Column(name = "reference_ids", columnDefinition = "TEXT") public String referenceIds;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) public ConversationDirection direction;
    @Column(length = 998) public String subject;
    @Column(length = 998) public String sender;
    @Column(columnDefinition = "TEXT") public String recipients;
    @Column(name = "sent_at") public Instant sentAt;
    @Column(columnDefinition = "TEXT") public String snippet;
    @Column(name = "payload_json", columnDefinition = "TEXT") public String payloadJson;
    @Column(name = "content_text", columnDefinition = "TEXT") public String contentText;
    @Column(name = "content_html", columnDefinition = "TEXT") public String contentHtml;
    @Column(name = "outbound_request_id", unique = true) public UUID outboundRequestId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "work_item_execution_id")
    public WorkItemExecutionEntity workItemExecution;
    @Column(name = "received_at", nullable = false) public Instant receivedAt;
}
