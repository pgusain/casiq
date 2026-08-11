package com.casiq.integration;

import com.casiq.usermanagement.api.AuthResource;
import com.casiq.usermanagement.domain.UserRole;
import com.casiq.usermanagement.persistence.ApplicationUserEntity;
import com.casiq.usermanagement.persistence.TenantEntity;
import com.casiq.usermanagement.security.PasswordService;
import com.casiq.workaccount.core.service.WorkAccountService;
import com.casiq.workaccount.core.persistence.EmailPollingConfigEntity;
import com.casiq.workaccount.core.persistence.ConversationDirection;
import com.casiq.workaccount.core.persistence.WorkAccountConversationEntity;
import com.casiq.workaccount.core.persistence.WorkAccountConversationAttachmentEntity;
import com.casiq.workaccount.core.persistence.WorkAccountEntity;
import com.casiq.workaccount.core.polling.EmailPollingStateService;
import com.casiq.workitem.conversation.ConversationWorkItemProcessor;
import com.casiq.workitem.conversation.ConversationWorkItemStateService;
import com.casiq.workitem.archive.WorkItemArchiveProcessor;
import com.casiq.workitem.archive.WorkItemArchiveStateService;
import com.casiq.workitem.persistence.WorkItemExecutionEntity;
import com.casiq.workitem.persistence.WorkItemStatusEntity;
import com.casiq.workitem.service.WorkItemEmailContentResolver;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class UserManagementResourceTest {
    private static final String INITIAL_PASSWORD = "password";
    private static final String ADMIN_PASSWORD = "P@ssword1234";

    @Inject PasswordService passwords;
    @Inject WorkAccountService workAccounts;
    @Inject EmailPollingStateService pollingState;
    @Inject ConversationWorkItemStateService conversationWorkItemState;
    @Inject ConversationWorkItemProcessor conversationWorkItemProcessor;
    @Inject WorkItemArchiveStateService workItemArchiveState;
    @Inject WorkItemArchiveProcessor workItemArchiveProcessor;
    @Inject WorkItemEmailContentResolver emailContentResolver;

    @Test
    void flywayCreatesTheInitialAdministratorOnAnEmptyDatabase() {
        login(new Seed("CASIQ", "admin"), "password")
                .then().statusCode(200)
                .body("role", equalTo("GLOBAL_ADMIN"))
                .body("mustChangePassword", equalTo(true));
    }

    @Test
    void loginForcesPasswordChangeAndAdminCanCreateAndResetAUser() {
        Seed admin = seed(UserRole.GLOBAL_ADMIN, true);

        given().contentType(ContentType.JSON)
                .body(loginBody(admin, "wrong-password"))
                .when().post("/api/v1/auth/login")
                .then().statusCode(401);

        String adminSession = login(admin, INITIAL_PASSWORD)
                .then().statusCode(200)
                .body("role", equalTo("GLOBAL_ADMIN"))
                .body("mustChangePassword", equalTo(true))
                .extract().cookie(AuthResource.SESSION_COOKIE);

        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/users")
                .then().statusCode(409)
                .body("error", containsString("Password change required"));

        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("currentPassword", INITIAL_PASSWORD, "newPassword", ADMIN_PASSWORD))
                .when().post("/api/v1/auth/password")
                .then().statusCode(200)
                .body("mustChangePassword", equalTo(false));

        String adminTenantId = given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/auth/me")
                .then().statusCode(200)
                .extract().jsonPath().getString("tenantId");
        String adminUserId = given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/auth/me")
                .then().statusCode(200)
                .extract().jsonPath().getString("id");

        String incomeTaxWorkItemId = given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .queryParam("tenantId", adminTenantId)
                .when().get("/api/v1/work-items/effective")
                .then().statusCode(200)
                .extract().jsonPath().getString("find { it.type == 'INCOME_TAX' }.id");
        String gstWorkItemId = given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .queryParam("tenantId", adminTenantId)
                .when().get("/api/v1/work-items/effective")
                .then().statusCode(200)
                .extract().jsonPath().getString("find { it.type == 'GST' }.id");
        String gstInitialStatusId = given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .queryParam("tenantId", adminTenantId)
                .when().get("/api/v1/work-items/effective")
                .then().statusCode(200)
                .extract().jsonPath().getString("find { it.type == 'GST' }.statuses.find { it.initialStatus }.id");

        Response workAccountCreated = given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("tenantId", adminTenantId, "emailId", "tax@example.com",
                        "provider", "GOOGLE", "workItemId", incomeTaxWorkItemId))
                .when().post("/api/v1/work-accounts");
        workAccountCreated.then().statusCode(200)
                .body("emailId", equalTo("tax@example.com"))
                .body("provider", equalTo("GOOGLE"))
                .body("workItemType", equalTo("INCOME_TAX"))
                .body("connected", equalTo(false));
        String workAccountId = workAccountCreated.jsonPath().getString("id");

        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("tenantId", adminTenantId, "emailId", "tax@example.com",
                        "provider", "GOOGLE", "workItemId", incomeTaxWorkItemId))
                .when().post("/api/v1/work-accounts")
                .then().statusCode(409);

        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("tenantId", adminTenantId, "emailId", "tax@example.com",
                        "provider", "GOOGLE", "workItemId", gstWorkItemId))
                .when().put("/api/v1/work-accounts/{id}", workAccountId)
                .then().statusCode(200)
                .body("workItemType", equalTo("GST"));

        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().post("/api/v1/work-accounts/{id}/authorize", workAccountId)
                .then().statusCode(200)
                .body("authorizationUrl", containsString("login_hint=tax%40example.com"));

        workAccounts.completeGmailConnection(Long.valueOf(workAccountId), "tax@example.com",
                "stored-access-token", "stored-refresh-token", Instant.now().plusSeconds(3600));
        QuarkusTransaction.requiringNew().run(() -> {
            WorkAccountEntity storedAccount = WorkAccountEntity.findById(Long.valueOf(workAccountId));
            EmailPollingConfigEntity polling = EmailPollingConfigEntity.find(
                    "workAccount.id", Long.valueOf(workAccountId)).firstResult();
            assertEquals("stored-refresh-token", storedAccount.refreshToken);
            assertEquals("GOOGLE", storedAccount.provider.code);
            assertNotNull(polling);
            assertEquals("stored-access-token", polling.accessToken);
            assertEquals("GOOGLE", polling.provider.code);
            assertNotNull(polling.accessTokenExpiresAt);
            assertNotNull(polling.nextRefreshAt);
        });
        Long pollingConfigId = QuarkusTransaction.requiringNew().call(() ->
                ((EmailPollingConfigEntity) EmailPollingConfigEntity.find(
                        "workAccount.id", Long.valueOf(workAccountId)).firstResult()).id);
        var firstClaim = pollingState.claimDue("test-instance-one", Instant.now().plusSeconds(1));
        assertTrue(firstClaim.contains(pollingConfigId));
        var competingClaim = pollingState.claimDue("test-instance-two", Instant.now().plusSeconds(1));
        assertFalse(competingClaim.contains(pollingConfigId));
        pollingState.fail(pollingConfigId, "test-instance-one", new IllegalStateException("test retry"));
        QuarkusTransaction.requiringNew().run(() -> {
            EmailPollingConfigEntity polling = EmailPollingConfigEntity.findById(pollingConfigId);
            assertEquals("test retry", polling.lastError);
            assertEquals(1, polling.consecutiveFailures);
            assertEquals(null, polling.lockOwner);
            assertNotNull(polling.nextRefreshAt);
        });

        Long conversationId = QuarkusTransaction.requiringNew().call(() -> {
            WorkAccountEntity account = WorkAccountEntity.findById(Long.valueOf(workAccountId));
            WorkAccountConversationEntity conversation = new WorkAccountConversationEntity();
            conversation.tenant = account.tenant;
            conversation.workAccount = account;
            conversation.provider = account.provider;
            conversation.providerMessageId = "gmail-" + UUID.randomUUID();
            conversation.providerThreadId = "gmail-thread-1";
            conversation.rfcMessageId = "<message-1@example.com>";
            conversation.direction = ConversationDirection.INBOUND;
            conversation.subject = "GST filing";
            conversation.sender = "sender@example.com";
            conversation.recipients = account.emailId;
            conversation.contentText = "Please review the attached GST filing request.";
            conversation.contentHtml = "<p>Please review the <strong>attached GST filing</strong> request.</p>";
            conversation.sentAt = Instant.now();
            conversation.receivedAt = Instant.now();
            Panache.getEntityManager().persist(conversation);
            WorkAccountConversationAttachmentEntity attachment =
                    new WorkAccountConversationAttachmentEntity();
            attachment.tenant = account.tenant;
            attachment.conversation = conversation;
            attachment.providerAttachmentId = "attachment-1";
            attachment.filename = "gst-filing.pdf";
            attachment.contentType = "application/pdf";
            attachment.contentData = "test-pdf-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            attachment.contentSize = attachment.contentData.length;
            attachment.createdAt = Instant.now();
            Panache.getEntityManager().persist(attachment);
            Panache.getEntityManager().flush();
            return conversation.id;
        });
        WorkItemEmailContentResolver.ResolvedContent tableContent =
                emailContentResolver.resolve(
                        new WorkItemEmailContentResolver.EmailReference(
                                Long.valueOf(adminTenantId),
                                Long.valueOf(workAccountId),
                                "GOOGLE",
                                QuarkusTransaction.requiringNew().call(() ->
                                        ((WorkAccountConversationEntity)
                                                WorkAccountConversationEntity.findById(
                                                        conversationId))
                                                .providerMessageId)));
        assertEquals("CONVERSATION_TABLE", tableContent.contentSource());
        assertTrue(tableContent.contentHtml().contains("<strong>"));

        var firstConversationClaim =
                conversationWorkItemState.claimDue("work-item-instance-one", Instant.now().plusSeconds(1));
        assertTrue(firstConversationClaim.contains(conversationId));
        var competingConversationClaim =
                conversationWorkItemState.claimDue("work-item-instance-two", Instant.now().plusSeconds(1));
        assertFalse(competingConversationClaim.contains(conversationId));
        conversationWorkItemProcessor.createExecution(conversationId, "work-item-instance-one");
        AtomicReference<String> conversationExecutionId = new AtomicReference<>();
        QuarkusTransaction.requiringNew().run(() -> {
            WorkItemExecutionEntity execution =
                    WorkItemExecutionEntity.find(
                            "workAccountId = ?1 and initialCommunicationId is not null",
                            Long.valueOf(workAccountId)).firstResult();
            assertNotNull(execution);
            conversationExecutionId.set(execution.id.toString());
            assertNull(execution.conversationId);
            assertNull(WorkAccountConversationEntity.findById(conversationId));
            assertEquals(Long.valueOf(workAccountId), execution.workAccountId);
            assertEquals("tax@example.com", execution.workAccountEmail);
            assertEquals("GST", execution.definition.type);
            assertEquals("AWAITING_FIRST_RESPONSE", execution.currentStatus.code);
            assertEquals("GST filing", execution.emailSubject);
            assertEquals("sender@example.com", execution.emailSender);
            assertEquals(null, execution.emailContentHtml);
            WorkItemStatusEntity awaitingCustomer = WorkItemStatusEntity.find(
                    "definition.id = ?1 and code = ?2",
                    execution.definition.id,
                    "AWAITING_CUSTOMER_RESPONSE").firstResult();
            ApplicationUserEntity assignedUser =
                    ApplicationUserEntity.findById(Long.valueOf(adminUserId));
            assertNotNull(awaitingCustomer);
            assertNotNull(assignedUser);
            execution.currentStatus = awaitingCustomer;
            execution.assignedUser = assignedUser;
            execution.assignedAt = Instant.now();
            execution.updatedAt = execution.assignedAt;
        });
        assertFalse(conversationWorkItemState.claimDue(
                "work-item-instance-three", Instant.now().plusSeconds(1)).contains(conversationId));

        Long followUpConversationId = QuarkusTransaction.requiringNew().call(() -> {
            WorkAccountEntity account = WorkAccountEntity.findById(Long.valueOf(workAccountId));
            WorkAccountConversationEntity conversation = new WorkAccountConversationEntity();
            conversation.tenant = account.tenant;
            conversation.workAccount = account;
            conversation.provider = account.provider;
            conversation.providerMessageId = "gmail-" + UUID.randomUUID();
            conversation.providerThreadId = "gmail-thread-1";
            conversation.rfcMessageId = "<message-2@example.com>";
            conversation.inReplyTo = "<message-1@example.com>";
            conversation.referenceIds = "<message-1@example.com>";
            conversation.direction = ConversationDirection.INBOUND;
            conversation.subject = "Re: GST filing";
            conversation.sender = "sender@example.com";
            conversation.recipients = account.emailId;
            conversation.contentText = "Here is the requested follow-up.";
            conversation.contentHtml = "<p>Here is the requested <em>follow-up</em>.</p>";
            conversation.sentAt = Instant.now().plusMillis(1);
            conversation.receivedAt = Instant.now().plusMillis(1);
            Panache.getEntityManager().persist(conversation);
            WorkAccountConversationAttachmentEntity attachment =
                    new WorkAccountConversationAttachmentEntity();
            attachment.tenant = account.tenant;
            attachment.conversation = conversation;
            attachment.providerAttachmentId = "attachment-2";
            attachment.filename = "gst-follow-up.txt";
            attachment.contentType = "text/plain";
            attachment.contentData = "follow-up-document".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            attachment.contentSize = attachment.contentData.length;
            attachment.createdAt = conversation.receivedAt;
            Panache.getEntityManager().persist(attachment);
            Panache.getEntityManager().flush();
            return conversation.id;
        });
        assertTrue(conversationWorkItemState.claimDue(
                "work-item-instance-four", Instant.now().plusSeconds(1))
                .contains(followUpConversationId));
        conversationWorkItemProcessor.createExecution(
                followUpConversationId, "work-item-instance-four");
        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(WorkAccountConversationEntity.findById(followUpConversationId));
            WorkItemExecutionEntity execution = WorkItemExecutionEntity.findById(
                    Long.valueOf(conversationExecutionId.get()));
            assertEquals(conversationExecutionId.get(),
                    execution.id.toString());
            assertEquals(
                    "READY_TO_PICK",
                    execution.currentStatus.code);
            assertEquals(
                    Long.valueOf(adminUserId),
                    execution.assignedUser.id);
            assertEquals(1L, WorkItemExecutionEntity.count(
                    "workAccountId = ?1 and definition.type = ?2 and initialCommunicationId is not null",
                    Long.valueOf(workAccountId), "GST"));
        });

        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(assignmentBody(
                        adminTenantId, gstWorkItemId, gstInitialStatusId, null, adminUserId))
                .when().post("/api/v1/work-items/assignments")
                .then().statusCode(200);

        Response emailWorkItem = given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/work-items/executions/{executionId}",
                        conversationExecutionId.get());
        emailWorkItem.then().statusCode(200)
                .body("execution.workItemNumber", org.hamcrest.Matchers.greaterThanOrEqualTo(100000))
                .body("conversation.subject", equalTo("GST filing"))
                .body("conversation.contentHtml", containsString("<strong>"))
                .body("communications.size()", equalTo(2))
                .body("communications.direction", hasItem("INBOUND"))
                .body("communications.subject", hasItem("Re: GST filing"))
                .body("documents.size()", equalTo(2))
                .body("documents[0].filename", equalTo("gst-filing.pdf"))
                .body("documents[0].origin", equalTo("INBOUND"))
                .body("documents.sourceConversationId",
                        hasItem(conversationId.intValue()))
                .body("documents.sourceConversationId",
                        hasItem(followUpConversationId.intValue()))
                .body("internalNotes.size()", equalTo(0));
        String documentId = emailWorkItem.jsonPath().getString("documents[0].id");

        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/work-items/executions/{executionId}/documents/{documentId}",
                        conversationExecutionId.get(), documentId)
                .then().statusCode(200)
                .header("Content-Disposition", containsString("gst-filing.pdf"))
                .body(equalTo("test-pdf-content"));

        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/work-items/executions/{executionId}",
                        conversationExecutionId.get())
                .then().statusCode(200)
                .body("conversation.subject", equalTo("GST filing"))
                .body("communications.size()", equalTo(2))
                .body("communications.contentSource", hasItem("CACHE"))
                .body("communications.subject", hasItem("Re: GST filing"))
                .body("documents.size()", equalTo(2))
                .body("documents.sourceConversationId",
                        hasItem(conversationId.intValue()))
                .body("documents.sourceConversationId",
                        hasItem(followUpConversationId.intValue()));

        Long postPurgeConversationId = QuarkusTransaction.requiringNew().call(() -> {
            WorkAccountEntity account = WorkAccountEntity.findById(Long.valueOf(workAccountId));
            WorkAccountConversationEntity conversation = new WorkAccountConversationEntity();
            conversation.tenant = account.tenant;
            conversation.workAccount = account;
            conversation.provider = account.provider;
            conversation.providerMessageId = "gmail-" + UUID.randomUUID();
            conversation.providerThreadId = "gmail-thread-1";
            conversation.rfcMessageId = "<message-3@example.com>";
            conversation.inReplyTo = "<message-2@example.com>";
            conversation.referenceIds = "<message-1@example.com> <message-2@example.com>";
            conversation.direction = ConversationDirection.INBOUND;
            conversation.subject = "Re: GST filing";
            conversation.sender = "sender@example.com";
            conversation.recipients = account.emailId;
            conversation.contentText = "This arrived after the materialized conversation was purged.";
            conversation.contentHtml =
                    "<p>This arrived after the materialized conversation was <strong>purged</strong>.</p>";
            conversation.sentAt = Instant.now().plusMillis(2);
            conversation.receivedAt = Instant.now().plusMillis(2);
            Panache.getEntityManager().persist(conversation);
            Panache.getEntityManager().flush();
            return conversation.id;
        });
        assertTrue(conversationWorkItemState.claimDue(
                "work-item-instance-five", Instant.now().plusSeconds(1))
                .contains(postPurgeConversationId));
        conversationWorkItemProcessor.createExecution(
                postPurgeConversationId, "work-item-instance-five");
        QuarkusTransaction.requiringNew().run(() -> {
            assertNull(WorkAccountConversationEntity.findById(postPurgeConversationId));
            WorkItemExecutionEntity execution = WorkItemExecutionEntity.findById(
                    Long.valueOf(conversationExecutionId.get()));
            assertEquals(conversationExecutionId.get(), execution.id.toString());
        });
        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/work-items/executions/{executionId}",
                        conversationExecutionId.get())
                .then().statusCode(200)
                .body("communications.size()", equalTo(3))
                .body("communications.contentHtml",
                        hasItem(containsString("after the materialized conversation")));

        Response internalUpload = given()
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .multiPart("file", "internal-review.txt",
                        "internal-team-document".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "text/plain")
                .when().post("/api/v1/work-items/executions/{executionId}/documents",
                        conversationExecutionId.get());
        internalUpload.then().statusCode(200)
                .body("filename", equalTo("internal-review.txt"))
                .body("origin", equalTo("INTERNAL"))
                .body("uploadedByUsername", equalTo(admin.username()));
        String internalDocumentId = internalUpload.jsonPath().getString("id");

        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/work-items/executions/{executionId}/documents/{documentId}",
                        conversationExecutionId.get(), internalDocumentId)
                .then().statusCode(200)
                .body(equalTo("internal-team-document"));

        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("content", "Reviewed internally; awaiting confirmation."))
                .when().post("/api/v1/work-items/executions/{executionId}/notes",
                        conversationExecutionId.get())
                .then().statusCode(200)
                .body("authorUsername", equalTo(admin.username()))
                .body("content", containsString("Reviewed internally"));

        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/work-items/executions/{executionId}",
                        conversationExecutionId.get())
                .then().statusCode(200)
                .body("internalNotes.size()", equalTo(1))
                .body("documents.origin", hasItem("INTERNAL"))
                .body("internalNotes[0].content", containsString("awaiting confirmation"));

        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/work-accounts")
                .then().statusCode(200)
                .body("connected", hasItem(true))
                .body("provider", hasItem("GOOGLE"))
                .body("nextRefreshAt", org.hamcrest.Matchers.notNullValue())
                .body("[0].accessToken", nullValue())
                .body("[0].refreshToken", nullValue());

        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/work-accounts/providers")
                .then().statusCode(200).body("code", hasItem("GOOGLE")).body("code", hasItem("MICROSOFT"));

        Long archivedExecutionId = Long.valueOf(conversationExecutionId.get());
        QuarkusTransaction.requiringNew().run(() -> {
            WorkItemExecutionEntity execution =
                    WorkItemExecutionEntity.findById(archivedExecutionId);
            WorkItemStatusEntity completed = WorkItemStatusEntity.find(
                    "definition.id = ?1 and code = ?2",
                    execution.definition.id,
                    "COMPLETED").firstResult();
            assertNotNull(completed);
            execution.currentStatus = completed;
            execution.updatedAt = Instant.now();
        });
        String archiveOwner = "work-item-archive-test";
        assertTrue(workItemArchiveState.claimCompleted(
                        archiveOwner, Instant.now().plusSeconds(1))
                .contains(archivedExecutionId));
        workItemArchiveProcessor.archive(archivedExecutionId, archiveOwner);
        QuarkusTransaction.requiringNew().run(() -> {
            WorkItemExecutionEntity execution =
                    WorkItemExecutionEntity.findById(archivedExecutionId);
            assertTrue(execution.dataMigrated);
            assertNotNull(execution.archivedAt);
            assertTrue(execution.archiveStorageKey.endsWith(
                    "work-items/" + archivedExecutionId + ".json"));
            Number remainingDetails = (Number) Panache.getEntityManager()
                    .createNativeQuery("""
                            SELECT
                              (SELECT COUNT(*) FROM work_item_communication WHERE execution_id = ?1)
                              + (SELECT COUNT(*) FROM work_item_document WHERE execution_id = ?1)
                              + (SELECT COUNT(*) FROM work_item_internal_note WHERE execution_id = ?1)
                              + (SELECT COUNT(*) FROM work_item_activity WHERE execution_id = ?1)
                            """)
                    .setParameter(1, archivedExecutionId)
                    .getSingleResult();
            assertEquals(0L, remainingDetails.longValue());
        });
        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get(
                        "/api/v1/work-items/executions/{executionId}",
                        archivedExecutionId)
                .then().statusCode(200)
                .body("execution.dataMigrated", equalTo(true))
                .body("execution.currentStatus", equalTo("COMPLETED"))
                .body("communications.size()", equalTo(3))
                .body("communications.contentHtml",
                        hasItem(containsString("<strong>")))
                .body("documents.size()", equalTo(3))
                .body("documents.origin", hasItem("INTERNAL"))
                .body("documents.sourceConversationId",
                        hasItem(conversationId.intValue()))
                .body("internalNotes.size()", equalTo(1))
                .body("internalNotes[0].content",
                        containsString("awaiting confirmation"));
        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get(
                        "/api/v1/work-items/executions/{executionId}/documents/{documentId}",
                        archivedExecutionId,
                        internalDocumentId)
                .then().statusCode(200)
                .body(equalTo("internal-team-document"));
        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("content", "Archived details must remain immutable."))
                .when().post(
                        "/api/v1/work-items/executions/{executionId}/notes",
                        archivedExecutionId)
                .then().statusCode(409)
                .body("error", containsString("read-only"));
        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .multiPart(
                        "file",
                        "late-document.txt",
                        "must-not-be-stored".getBytes(
                                java.nio.charset.StandardCharsets.UTF_8),
                        "text/plain")
                .when().post(
                        "/api/v1/work-items/executions/{executionId}/documents",
                        archivedExecutionId)
                .then().statusCode(409)
                .body("error", containsString("read-only"));

        String tenantCode = "CLIENT-" + UUID.randomUUID().toString().substring(0, 8);
        Response tenantCreated = given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("companyCode", tenantCode, "displayName", "Client Workspace", "active", true))
                .when().post("/api/v1/tenants");
        tenantCreated.then().statusCode(200)
                .body("companyCode", equalTo(tenantCode))
                .body("active", equalTo(true));
        String tenantId = tenantCreated.jsonPath().getString("id");

        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("companyCode", tenantCode, "displayName", "Duplicate", "active", true))
                .when().post("/api/v1/tenants")
                .then().statusCode(409);

        String updatedTenantCode = tenantCode + "-NEW";
        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("companyCode", updatedTenantCode, "displayName", "Updated Client", "active", false))
                .when().put("/api/v1/tenants/{id}", tenantId)
                .then().statusCode(200)
                .body("companyCode", equalTo(updatedTenantCode))
                .body("displayName", equalTo("Updated Client"))
                .body("active", equalTo(false));

        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/tenants")
                .then().statusCode(200)
                .body("companyCode", hasItem(updatedTenantCode));

        Response created = given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of(
                        "companyCode", admin.companyCode(),
                        "username", "processor.user",
                        "firstName", "Priya",
                        "lastName", "Processor",
                        "temporaryPassword", "ProcessorTemp-123",
                        "role", "PROCESSOR"))
                .when().post("/api/v1/users");
        created.then().statusCode(200)
                .body("mustChangePassword", equalTo(true))
                .body("firstName", equalTo("Priya"))
                .body("lastName", equalTo("Processor"))
                .body("role", equalTo("PROCESSOR"));
        String processorId = created.jsonPath().getString("id");

        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of(
                        "username", "processor.updated",
                        "firstName", "Priyanka",
                        "lastName", "Reviewer",
                        "role", "BASE_USER",
                        "active", true))
                .when().put("/api/v1/users/{id}", processorId)
                .then().statusCode(200)
                .body("username", equalTo("processor.updated"))
                .body("firstName", equalTo("Priyanka"))
                .body("lastName", equalTo("Reviewer"))
                .body("role", equalTo("BASE_USER"))
                .body("active", equalTo(true));

        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/users")
                .then().statusCode(200)
                .body("username", hasItem("processor.updated"))
                .body("role", hasItem("BASE_USER"));

        String processorSession = login(
                new Seed(admin.companyCode(), "processor.updated"), "ProcessorTemp-123")
                .then().statusCode(200)
                .extract().cookie(AuthResource.SESSION_COOKIE);

        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("temporaryPassword", "ProcessorReset-456"))
                .when().post("/api/v1/users/{id}/reset-password", processorId)
                .then().statusCode(200)
                .body("mustChangePassword", equalTo(true));

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .when().get("/api/v1/auth/me")
                .then().statusCode(401);

        login(new Seed(admin.companyCode(), "processor.updated"), "ProcessorReset-456")
                .then().statusCode(200)
                .body("mustChangePassword", equalTo(true));
    }

    @Test
    void tenantAdministrationRequiresGlobalAdmin() {
        Seed administrator = seed(UserRole.ADMIN, false);
        String session = login(administrator, INITIAL_PASSWORD)
                .then().statusCode(200)
                .extract().cookie(AuthResource.SESSION_COOKIE);
        String administratorId = given().cookie(AuthResource.SESSION_COOKIE, session)
                .when().get("/api/v1/auth/me")
                .then().statusCode(200)
                .extract().jsonPath().getString("id");

        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, session)
                .body(Map.of(
                        "username", administrator.username(),
                        "firstName", "Test",
                        "lastName", "User",
                        "role", "PROCESSOR",
                        "active", true))
                .when().put("/api/v1/users/{id}", administratorId)
                .then().statusCode(400)
                .body("error", containsString("own role"));

        given().cookie(AuthResource.SESSION_COOKIE, session)
                .when().get("/api/v1/tenants")
                .then().statusCode(403)
                .body("error", containsString("GLOBAL_ADMIN"));

        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, session)
                .body(Map.of("companyCode", "FORBIDDEN", "displayName", "Forbidden", "active", true))
                .when().post("/api/v1/tenants")
                .then().statusCode(403);

        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, session)
                .body(workItemBody(null, true, "FORBIDDEN_ITEM"))
                .when().post("/api/v1/work-items/definitions")
                .then().statusCode(403)
                .body("error", containsString("GLOBAL_ADMIN"));

        given().cookie(AuthResource.SESSION_COOKIE, session)
                .when().get("/api/v1/work-items/effective")
                .then().statusCode(200).body("type", hasItem("INCOME_TAX"));
    }

    @Test
    void workItemNumbersAreNumericSequencesScopedToEachTenant() {
        Seed firstAdmin = seed(UserRole.ADMIN, false);
        Seed secondAdmin = seed(UserRole.ADMIN, false);
        String firstSession = login(firstAdmin, INITIAL_PASSWORD)
                .then().statusCode(200).extract().cookie(AuthResource.SESSION_COOKIE);
        String secondSession = login(secondAdmin, INITIAL_PASSWORD)
                .then().statusCode(200).extract().cookie(AuthResource.SESSION_COOKIE);

        Long firstTenantId = tenantId(firstSession);
        Long secondTenantId = tenantId(secondSession);
        String firstDefinitionId = incomeTaxDefinitionId(firstSession);
        String secondDefinitionId = incomeTaxDefinitionId(secondSession);

        Long firstAccountId = createWorkAccount(
                firstSession, firstTenantId, firstDefinitionId, "tenant-one-first@example.com");
        Long firstTenantSecondAccountId = createWorkAccount(
                firstSession, firstTenantId, firstDefinitionId, "tenant-one-second@example.com");
        Long secondAccountId = createWorkAccount(
                secondSession, secondTenantId, secondDefinitionId, "tenant-two-first@example.com");

        QuarkusTransaction.requiringNew().run(() -> {
            WorkItemExecutionEntity first = WorkItemExecutionEntity.find(
                    "workAccountId = ?1 and conversationId is null", firstAccountId).firstResult();
            WorkItemExecutionEntity firstTenantSecond = WorkItemExecutionEntity.find(
                    "workAccountId = ?1 and conversationId is null",
                    firstTenantSecondAccountId).firstResult();
            WorkItemExecutionEntity second = WorkItemExecutionEntity.find(
                    "workAccountId = ?1 and conversationId is null", secondAccountId).firstResult();

            assertNotNull(first);
            assertNotNull(firstTenantSecond);
            assertNotNull(second);
            assertEquals(100000L, first.workItemNumber);
            assertEquals(100001L, firstTenantSecond.workItemNumber);
            assertEquals(100000L, second.workItemNumber);
        });
    }

    @Test
    void globalAdminDefinesGraphsAndTenantOverridesShadowGlobalDefinitions() {
        Seed admin = seed(UserRole.GLOBAL_ADMIN, false);
        String session = login(admin, INITIAL_PASSWORD).then().statusCode(200)
                .extract().cookie(AuthResource.SESSION_COOKIE);
        String type = "AUDIT_" + UUID.randomUUID().toString().substring(0, 8).replace('-', '_').toUpperCase();

        Response global = given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, session)
                .body(workItemBody(null, true, type))
                .when().post("/api/v1/work-items/definitions");
        global.then().statusCode(200).body("globalScope", equalTo(true))
                .body("statuses.size()", equalTo(6))
                .body("statuses.code", org.hamcrest.Matchers.hasItems(
                        "AWAITING_FIRST_RESPONSE",
                        "READY_TO_PICK",
                        "IN_PROGRESS",
                        "AWAITING_CUSTOMER_RESPONSE",
                        "CANCELLED",
                        "COMPLETED"));
        String globalId = global.jsonPath().getString("id");

        Map<String, Object> updatedGlobal = workItemBody(null, true, type);
        updatedGlobal.put("displayName", "Updated audit workflow");
        given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, session).body(updatedGlobal)
                .when().put("/api/v1/work-items/definitions/{id}", globalId)
                .then().statusCode(200).body("displayName", equalTo("Updated audit workflow"));

        String tenantCode = "GRAPH-" + UUID.randomUUID().toString().substring(0, 8);
        String tenantId = given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, session)
                .body(Map.of("companyCode", tenantCode, "displayName", "Graph tenant", "active", true))
                .when().post("/api/v1/tenants").then().statusCode(200).extract().jsonPath().getString("id");

        Response override = given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, session)
                .body(workItemBody(tenantId, false, type))
                .when().post("/api/v1/work-items/definitions");
        override.then().statusCode(200).body("globalScope", equalTo(false))
                .body("overridesDefinitionId", equalTo(Integer.valueOf(globalId)));
        String overrideId = override.jsonPath().getString("id");

        given().cookie(AuthResource.SESSION_COOKIE, session).queryParam("tenantId", tenantId)
                .when().get("/api/v1/work-items/effective")
                .then().statusCode(200)
                .body("find { it.type == '" + type + "' }.id", equalTo(Integer.valueOf(overrideId)));

        Map<String, Object> invalid = workItemBody(null, true, "BROKEN_" + type);
        invalid.put("transitions", List.of());
        given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, session).body(invalid)
                .when().post("/api/v1/work-items/definitions")
                .then().statusCode(400).body("error", containsString("reachable"));
    }

    @Test
    void assignedUserPerformsStatusAndTransitionActivitiesWithinTheirTenant() {
        Seed admin = seed(UserRole.ADMIN, false);
        String adminSession = login(admin, INITIAL_PASSWORD).then().statusCode(200)
                .extract().cookie(AuthResource.SESSION_COOKIE);
        String tenantId = given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/auth/me").then().statusCode(200).extract().jsonPath().getString("tenantId");

        Response definition = given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/work-items/effective").then().statusCode(200)
                .extract().response();
        String definitionId = definition.jsonPath().getString("find { it.type == 'INCOME_TAX' }.id");
        String gstDefinitionId = definition.jsonPath().getString(
                "find { it.type == 'GST' }.id");
        String gstInProgressStatusId = definition.jsonPath().getString(
                "find { it.type == 'GST' }.statuses.find { it.code == 'IN_PROGRESS' }.id");
        String initialStatusId = definition.jsonPath().getString(
                "find { it.type == 'INCOME_TAX' }.statuses.find { it.initialStatus }.id");
        String startTransitionId = definition.jsonPath().getString(
                "find { it.type == 'INCOME_TAX' }.transitions.find { it.fromStatus == 'AWAITING_FIRST_RESPONSE' }.id");
        String completeTransitionId = definition.jsonPath().getString(
                "find { it.type == 'INCOME_TAX' }.transitions.find { it.toStatus == 'COMPLETED' }.id");

        String processorId = given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("companyCode", admin.companyCode(), "username", "workflow.processor",
                        "firstName", "Workflow", "lastName", "Processor",
                        "temporaryPassword", "WorkflowTemp-123", "role", "PROCESSOR"))
                .when().post("/api/v1/users").then().statusCode(200).extract().jsonPath().getString("id");

        Response account = given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("tenantId", tenantId, "emailId", "workflow@example.com",
                        "provider", "MICROSOFT", "workItemId", definitionId))
                .when().post("/api/v1/work-accounts");
        account.then().statusCode(200);
        String microsoftAccountId = account.jsonPath().getString("id");
        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().post("/api/v1/work-accounts/{id}/authorize", microsoftAccountId)
                .then().statusCode(501).body("error", containsString("Microsoft"));

        given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(assignmentBody(tenantId, definitionId, initialStatusId, null, processorId))
                .when().post("/api/v1/work-items/assignments")
                .then().statusCode(200).body("assignmentType", equalTo("STATUS"));
        given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(assignmentBody(tenantId, definitionId, null, completeTransitionId, processorId))
                .when().post("/api/v1/work-items/assignments")
                .then().statusCode(200).body("assignmentType", equalTo("TRANSITION"));

        String processorSession = login(new Seed(admin.companyCode(), "workflow.processor"), "WorkflowTemp-123")
                .then().statusCode(200).extract().cookie(AuthResource.SESSION_COOKIE);
        given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, processorSession)
                .body(Map.of("currentPassword", "WorkflowTemp-123", "newPassword", "WorkflowSecure-456"))
                .when().post("/api/v1/auth/password").then().statusCode(200);

        String secondProcessorId = given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of(
                        "companyCode", admin.companyCode(),
                        "username", "workflow.processor.two",
                        "firstName", "Second",
                        "lastName", "Processor",
                        "temporaryPassword", "WorkflowTemp-789",
                        "role", "PROCESSOR"))
                .when().post("/api/v1/users").then().statusCode(200)
                .extract().jsonPath().getString("id");
        given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(assignmentBody(
                        tenantId, definitionId, null,
                        completeTransitionId, secondProcessorId))
                .when().post("/api/v1/work-items/assignments")
                .then().statusCode(200);
        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(assignmentBody(
                        tenantId, gstDefinitionId, gstInProgressStatusId,
                        null, secondProcessorId))
                .when().post("/api/v1/work-items/assignments")
                .then().statusCode(200);
        String secondProcessorSession = login(
                new Seed(admin.companyCode(), "workflow.processor.two"),
                "WorkflowTemp-789")
                .then().statusCode(200)
                .extract().cookie(AuthResource.SESSION_COOKIE);
        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, secondProcessorSession)
                .body(Map.of(
                        "currentPassword", "WorkflowTemp-789",
                        "newPassword", "WorkflowSecure-789"))
                .when().post("/api/v1/auth/password").then().statusCode(200);

        Response myWork = given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .queryParam("page", 0)
                .queryParam("size", 1)
                .queryParam("sortBy", "email")
                .queryParam("sortDirection", "asc")
                .when().get("/api/v1/work-items/my-work");
        myWork.then().statusCode(200)
                .body("page", equalTo(0))
                .body("size", equalTo(1))
                .body("totalElements", equalTo(1))
                .body("sortBy", equalTo("email"))
                .body("sortDirection", equalTo("asc"))
                .body("items[0].emailId", equalTo("workflow@example.com"))
                .body("items[0].workItemNumber", org.hamcrest.Matchers.greaterThanOrEqualTo(100000))
                .body("items[0].currentStatus", equalTo("AWAITING_FIRST_RESPONSE"))
                .body("items[0].allowedTransitions[0].id", equalTo(Integer.valueOf(startTransitionId)));
        String executionId = myWork.jsonPath().getString("items[0].id");
        long versionBeforeAssignment = QuarkusTransaction.requiringNew().call(() ->
                ((WorkItemExecutionEntity) WorkItemExecutionEntity.findById(
                        Long.valueOf(executionId))).version);

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .when().get("/api/v1/work-items/my-work/status-summary")
                .then().statusCode(200)
                .body("status", hasItem("AWAITING_FIRST_RESPONSE"))
                .body("find { it.status == 'AWAITING_FIRST_RESPONSE' }.count", equalTo(1));

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .when().post("/api/v1/work-items/executions/{executionId}/transitions/{transitionId}",
                        executionId, startTransitionId)
                .then().statusCode(200)
                .body("currentStatus", equalTo("IN_PROGRESS"))
                .body("assignedUsername", equalTo("workflow.processor"))
                .body("assignedToCurrentUser", equalTo(true));
        long versionAfterAssignment = QuarkusTransaction.requiringNew().call(() ->
                ((WorkItemExecutionEntity) WorkItemExecutionEntity.findById(
                        Long.valueOf(executionId))).version);
        assertTrue(versionAfterAssignment > versionBeforeAssignment);

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .when().get("/api/v1/work-items/effective")
                .then().statusCode(200)
                .body("type", hasItem("GST"));
        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, processorSession)
                .body(Map.of("definitionId", gstDefinitionId))
                .when().put(
                        "/api/v1/work-items/executions/{executionId}/type",
                        executionId)
                .then().statusCode(200)
                .body("workItemType", equalTo("GST"))
                .body("currentStatus", equalTo("IN_PROGRESS"))
                .body("assignedUsername", equalTo("workflow.processor"))
                .body("allowedTransitions.size()", equalTo(0));
        long versionAfterTypeChange = QuarkusTransaction.requiringNew().call(() ->
                ((WorkItemExecutionEntity) WorkItemExecutionEntity.findById(
                        Long.valueOf(executionId))).version);
        assertTrue(versionAfterTypeChange > versionAfterAssignment);
        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .queryParam("queueScope", "MY")
                .when().get("/api/v1/work-items/my-work")
                .then().statusCode(200)
                .body("totalElements", equalTo(0));
        given().cookie(AuthResource.SESSION_COOKIE, secondProcessorSession)
                .queryParam("queueScope", "OTHER")
                .when().get("/api/v1/work-items/my-work")
                .then().statusCode(200)
                .body("totalElements", equalTo(1))
                .body("items[0].workItemType", equalTo("GST"));
        given().cookie(AuthResource.SESSION_COOKIE, secondProcessorSession)
                .queryParam("force", true)
                .when().post(
                        "/api/v1/work-items/executions/{executionId}/pick",
                        executionId)
                .then().statusCode(200);
        given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, secondProcessorSession)
                .body(Map.of("definitionId", definitionId))
                .when().put(
                        "/api/v1/work-items/executions/{executionId}/type",
                        executionId)
                .then().statusCode(200)
                .body("workItemType", equalTo("INCOME_TAX"))
                .body("currentStatus", equalTo("IN_PROGRESS"))
                .body("allowedTransitions.id",
                        hasItem(Integer.valueOf(completeTransitionId)));
        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .queryParam("force", true)
                .when().post(
                        "/api/v1/work-items/executions/{executionId}/pick",
                        executionId)
                .then().statusCode(200)
                .body("execution.assignedUsername",
                        equalTo("workflow.processor"));

        // Forced takeover is allowed for another authenticated tenant user even
        // when that user has no workflow transition assignment.
        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .queryParam("force", true)
                .when().post("/api/v1/work-items/executions/{executionId}/pick", executionId)
                .then().statusCode(200)
                .body("reassigned", equalTo(true))
                .body("execution.assignedUsername", equalTo(admin.username()));
        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .queryParam("queueScope", "MY")
                .when().get("/api/v1/work-items/my-work")
                .then().statusCode(200)
                .body("totalElements", equalTo(0))
                .body("items.size()", equalTo(0));
        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .queryParam("queueScope", "MY")
                .when().get("/api/v1/work-items/my-work/status-summary")
                .then().statusCode(200)
                .body("size()", equalTo(0));
        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .queryParam("force", true)
                .when().post("/api/v1/work-items/executions/{executionId}/pick", executionId)
                .then().statusCode(200)
                .body("execution.assignedUsername", equalTo("workflow.processor"));

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .queryParam("queueScope", "MY")
                .when().get("/api/v1/work-items/my-work")
                .then().statusCode(200)
                .body("totalElements", equalTo(1));
        given().cookie(AuthResource.SESSION_COOKIE, secondProcessorSession)
                .queryParam("queueScope", "OTHER")
                .when().get("/api/v1/work-items/my-work")
                .then().statusCode(200)
                .body("totalElements", equalTo(1))
                .body("items[0].assignedUsername", equalTo("workflow.processor"));
        given().cookie(AuthResource.SESSION_COOKIE, secondProcessorSession)
                .when().get("/api/v1/work-items/executions/{executionId}", executionId)
                .then().statusCode(200)
                .body("readOnly", equalTo(true));
        given().cookie(AuthResource.SESSION_COOKIE, secondProcessorSession)
                .when().post("/api/v1/work-items/executions/{executionId}/pick", executionId)
                .then().statusCode(409)
                .body("error", containsString("workflow.processor"));
        given().cookie(AuthResource.SESSION_COOKIE, secondProcessorSession)
                .queryParam("force", true)
                .when().post("/api/v1/work-items/executions/{executionId}/pick", executionId)
                .then().statusCode(200)
                .body("reassigned", equalTo(true))
                .body("execution.assignedUsername", equalTo("workflow.processor.two"));
        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .queryParam("force", true)
                .when().post("/api/v1/work-items/executions/{executionId}/pick", executionId)
                .then().statusCode(200)
                .body("execution.assignedUsername", equalTo("workflow.processor"));

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .when().get("/api/v1/work-items/my-work")
                .then().statusCode(200)
                .body("items[0].allowedTransitions[0].id", equalTo(Integer.valueOf(completeTransitionId)))
                .body("items[0].activities[0].fromStatus", equalTo("AWAITING_FIRST_RESPONSE"))
                .body("items[0].activities[0].performedByUsername", equalTo("workflow.processor"));

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .when().post("/api/v1/work-items/executions/{executionId}/transitions/{transitionId}",
                        executionId, completeTransitionId)
                .then().statusCode(200)
                .body("currentStatus", equalTo("COMPLETED"))
                .body("terminal", equalTo(true));

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .when().get("/api/v1/work-items/my-work")
                .then().statusCode(200)
                .body("totalElements", equalTo(0))
                .body("items.size()", equalTo(0));

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .queryParam("includeTerminal", true)
                .queryParam("workItemType", "income_tax")
                .queryParam("status", "completed")
                .queryParam("email", "workflow@")
                .when().get("/api/v1/work-items/my-work")
                .then().statusCode(200)
                .body("totalElements", equalTo(1))
                .body("items.size()", equalTo(1))
                .body("items[0].id", equalTo(Integer.valueOf(executionId)))
                .body("items[0].terminal", equalTo(true));

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .queryParam("includeTerminal", true)
                .when().get("/api/v1/work-items/my-work/status-summary")
                .then().statusCode(200)
                .body("status", hasItem("COMPLETED"))
                .body("find { it.status == 'COMPLETED' }.count", equalTo(1));

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .when().get("/api/v1/work-items/executions/{executionId}", executionId)
                .then().statusCode(200)
                .body("execution.id", equalTo(Integer.valueOf(executionId)))
                .body("execution.terminal", equalTo(true))
                .body("conversation", nullValue());

        given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, processorSession)
                .body(assignmentBody(tenantId, definitionId, initialStatusId, null, processorId))
                .when().post("/api/v1/work-items/assignments")
                .then().statusCode(403);
    }

    @Test
    void loginRequiresTheCorrectCompanyCodeAndTheUiIsAvailable() {
        Seed user = seed(UserRole.BASE_USER, false);

        given().contentType(ContentType.JSON)
                .body(Map.of(
                        "companyCode", "another-company",
                        "username", user.username(),
                        "password", INITIAL_PASSWORD))
                .when().post("/api/v1/auth/login")
                .then().statusCode(401);

        login(user, INITIAL_PASSWORD)
                .then().statusCode(200)
                .body("companyCode", equalTo(user.companyCode()))
                .body("role", equalTo("BASE_USER"));

        given().when().get("/")
                .then().statusCode(200)
                .body(containsString("Company code"))
                .body(containsString("Change password"))
                .body(containsString("Work items"))
                .body(containsString("Filters and sorting"))
                .body(containsString("my-work-status-summary"))
                .body(containsString("navigate-administration"))
                .body(containsString("Work item definitions"))
                .body(containsString("Workflow assignments"))
                .body(containsString("Work accounts"))
                .body(containsString("First name"))
                .body(containsString("Last name"))
                .body(containsString("Edit user"))
                .body(containsString("Include completed work"))
                .body(containsString("work-item-detail"))
                .body(containsString("work-detail-type-form"))
                .body(containsString("Documents"))
                .body(containsString("Internal notes"))
                .body(containsString("Reply to sender"))
                .body(containsString("work-reply-files"))
                .body(containsString("work-reply-editor"))
                .body(containsString("/assets/casiq-logo.png"));

        given().when().get("/app.js")
                .then().statusCode(200)
                .body(containsString(
                        "assignmentWorkItems.find(item => String(item.id) === selectedDefinitionId)"))
                .body(containsString("renderAssignmentWorkflowGroup"))
                .body(containsString(
                        "work-account-panel').classList.toggle('hidden', !tenantAdmin)"));

        given().when().get("/gmail/")
                .then().statusCode(200)
                .body(containsString("GOOGLE OAUTH CONNECTOR"));

        given().when().get("/assets/casiq-logo.png")
                .then().statusCode(200)
                .contentType("image/png");
    }

    private Response login(Seed seed, String password) {
        return given().contentType(ContentType.JSON)
                .body(loginBody(seed, password))
                .when().post("/api/v1/auth/login");
    }

    private Long tenantId(String session) {
        return given().cookie(AuthResource.SESSION_COOKIE, session)
                .when().get("/api/v1/auth/me")
                .then().statusCode(200)
                .extract().<Number>path("tenantId").longValue();
    }

    private String incomeTaxDefinitionId(String session) {
        return given().cookie(AuthResource.SESSION_COOKIE, session)
                .when().get("/api/v1/work-items/effective")
                .then().statusCode(200)
                .extract().jsonPath().getString("find { it.type == 'INCOME_TAX' }.id");
    }

    private Long createWorkAccount(
            String session, Long tenantId, String definitionId, String email) {
        return given().contentType(ContentType.JSON)
                .cookie(AuthResource.SESSION_COOKIE, session)
                .body(Map.of(
                        "tenantId", tenantId.toString(),
                        "emailId", email,
                        "provider", "GOOGLE",
                        "workItemId", definitionId))
                .when().post("/api/v1/work-accounts")
                .then().statusCode(200)
                .extract().<Number>path("id").longValue();
    }

    private Map<String, String> loginBody(Seed seed, String password) {
        return Map.of("companyCode", seed.companyCode(), "username", seed.username(), "password", password);
    }

    private Map<String, Object> workItemBody(String tenantId, boolean globalScope, String type) {
        Map<String, Object> body = new java.util.HashMap<>();
        if (tenantId != null) body.put("tenantId", tenantId);
        body.put("globalScope", globalScope);
        body.put("type", type);
        body.put("displayName", type.replace('_', ' '));
        body.put("active", true);
        body.put("transitions", List.of(
                Map.of("fromStatus", "AWAITING_FIRST_RESPONSE", "toStatus", "IN_PROGRESS", "label", "Start work"),
                Map.of("fromStatus", "IN_PROGRESS", "toStatus", "AWAITING_CUSTOMER_RESPONSE", "label", "Request response"),
                Map.of("fromStatus", "IN_PROGRESS", "toStatus", "COMPLETED", "label", "Complete"),
                Map.of("fromStatus", "IN_PROGRESS", "toStatus", "CANCELLED", "label", "Cancel"),
                Map.of("fromStatus", "AWAITING_CUSTOMER_RESPONSE", "toStatus", "READY_TO_PICK", "label", "Customer responded"),
                Map.of("fromStatus", "READY_TO_PICK", "toStatus", "IN_PROGRESS", "label", "Start work")));
        return body;
    }

    private Map<String, Object> assignmentBody(String tenantId, String definitionId, String statusId,
                                               String transitionId, String userId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("tenantId", tenantId);
        body.put("definitionId", definitionId);
        body.put("statusId", statusId);
        body.put("transitionId", transitionId);
        body.put("userId", userId);
        return body;
    }

    private Seed seed(UserRole role, boolean mustChangePassword) {
        AtomicReference<Seed> result = new AtomicReference<>();
        QuarkusTransaction.requiringNew().run(() -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            String companyCode = "TEST-" + suffix;
            String username = "user-" + suffix;
            Instant now = Instant.now();

            TenantEntity tenant = new TenantEntity();
            tenant.companyCode = companyCode;
            tenant.normalizedCompanyCode = companyCode.toLowerCase();
            tenant.displayName = companyCode;
            tenant.active = true;
            tenant.createdAt = now;
            tenant.updatedAt = now;
            tenant.persist();

            ApplicationUserEntity user = new ApplicationUserEntity();
            user.tenant = tenant;
            user.username = username;
            user.normalizedUsername = username.toLowerCase();
            user.firstName = "Test";
            user.lastName = "User";
            user.passwordHash = passwords.hash(INITIAL_PASSWORD);
            user.role = role;
            user.mustChangePassword = mustChangePassword;
            user.active = true;
            user.createdAt = now;
            user.updatedAt = now;
            user.persist();
            result.set(new Seed(companyCode, username));
        });
        return result.get();
    }

    private record Seed(String companyCode, String username) {}
}
