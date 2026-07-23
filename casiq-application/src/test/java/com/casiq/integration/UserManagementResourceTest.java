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
import com.casiq.workaccount.core.persistence.WorkAccountEntity;
import com.casiq.workaccount.core.polling.EmailPollingStateService;
import com.casiq.workitem.conversation.ConversationWorkItemProcessor;
import com.casiq.workitem.conversation.ConversationWorkItemStateService;
import com.casiq.workitem.persistence.WorkItemExecutionEntity;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class UserManagementResourceTest {
    private static final String INITIAL_PASSWORD = "InitialPass-123";
    private static final String ADMIN_PASSWORD = "AdminSecure-456";

    @Inject PasswordService passwords;
    @Inject WorkAccountService workAccounts;
    @Inject EmailPollingStateService pollingState;
    @Inject ConversationWorkItemStateService conversationWorkItemState;
    @Inject ConversationWorkItemProcessor conversationWorkItemProcessor;

    @Test
    void flywayCreatesTheInitialAdministratorOnAnEmptyDatabase() {
        login(new Seed("TESTROOT", "initial.admin"), "casiq-dummy-password-never-used")
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
                .extract().path("tenantId");

        String incomeTaxWorkItemId = given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .queryParam("tenantId", adminTenantId)
                .when().get("/api/v1/work-items/effective")
                .then().statusCode(200)
                .extract().path("find { it.type == 'INCOME_TAX' }.id");
        String gstWorkItemId = given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .queryParam("tenantId", adminTenantId)
                .when().get("/api/v1/work-items/effective")
                .then().statusCode(200)
                .extract().path("find { it.type == 'GST' }.id");

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

        workAccounts.completeGmailConnection(UUID.fromString(workAccountId), "tax@example.com",
                "stored-access-token", "stored-refresh-token", Instant.now().plusSeconds(3600));
        QuarkusTransaction.requiringNew().run(() -> {
            WorkAccountEntity storedAccount = WorkAccountEntity.findById(UUID.fromString(workAccountId));
            EmailPollingConfigEntity polling = EmailPollingConfigEntity.find(
                    "workAccount.id", UUID.fromString(workAccountId)).firstResult();
            assertEquals("stored-refresh-token", storedAccount.refreshToken);
            assertEquals("GOOGLE", storedAccount.provider.code);
            assertNotNull(polling);
            assertEquals("stored-access-token", polling.accessToken);
            assertEquals("GOOGLE", polling.provider.code);
            assertNotNull(polling.accessTokenExpiresAt);
            assertNotNull(polling.nextRefreshAt);
        });
        UUID pollingConfigId = QuarkusTransaction.requiringNew().call(() ->
                ((EmailPollingConfigEntity) EmailPollingConfigEntity.find(
                        "workAccount.id", UUID.fromString(workAccountId)).firstResult()).id);
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

        UUID conversationId = QuarkusTransaction.requiringNew().call(() -> {
            WorkAccountEntity account = WorkAccountEntity.findById(UUID.fromString(workAccountId));
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
            conversation.sentAt = Instant.now();
            conversation.receivedAt = Instant.now();
            Panache.getEntityManager().persist(conversation);
            Panache.getEntityManager().flush();
            return conversation.id;
        });
        var firstConversationClaim =
                conversationWorkItemState.claimDue("work-item-instance-one", Instant.now().plusSeconds(1));
        assertTrue(firstConversationClaim.contains(conversationId));
        var competingConversationClaim =
                conversationWorkItemState.claimDue("work-item-instance-two", Instant.now().plusSeconds(1));
        assertFalse(competingConversationClaim.contains(conversationId));
        conversationWorkItemProcessor.createExecution(conversationId, "work-item-instance-one");
        QuarkusTransaction.requiringNew().run(() -> {
            WorkItemExecutionEntity execution =
                    WorkItemExecutionEntity.find("conversationId", conversationId).firstResult();
            assertNotNull(execution);
            assertEquals(UUID.fromString(workAccountId), execution.workAccountId);
            assertEquals("tax@example.com", execution.workAccountEmail);
            assertEquals("GST", execution.definition.type);
            assertEquals("NEW", execution.currentStatus.code);
            Object processedAt = Panache.getEntityManager().createNativeQuery("""
                            SELECT work_item_processed_at
                            FROM work_account_conversation
                            WHERE id = ?1
                            """)
                    .setParameter(1, conversationId)
                    .getSingleResult();
            assertNotNull(processedAt);
        });
        assertFalse(conversationWorkItemState.claimDue(
                "work-item-instance-three", Instant.now().plusSeconds(1)).contains(conversationId));

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
                        "temporaryPassword", "ProcessorTemp-123",
                        "role", "PROCESSOR"))
                .when().post("/api/v1/users");
        created.then().statusCode(200)
                .body("mustChangePassword", equalTo(true))
                .body("role", equalTo("PROCESSOR"));
        String processorId = created.jsonPath().getString("id");

        given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/users")
                .then().statusCode(200)
                .body("role", hasItem("PROCESSOR"));

        String processorSession = login(
                new Seed(admin.companyCode(), "processor.user"), "ProcessorTemp-123")
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

        login(new Seed(admin.companyCode(), "processor.user"), "ProcessorReset-456")
                .then().statusCode(200)
                .body("mustChangePassword", equalTo(true));
    }

    @Test
    void tenantAdministrationRequiresGlobalAdmin() {
        Seed administrator = seed(UserRole.ADMIN, false);
        String session = login(administrator, INITIAL_PASSWORD)
                .then().statusCode(200)
                .extract().cookie(AuthResource.SESSION_COOKIE);

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
    void globalAdminDefinesGraphsAndTenantOverridesShadowGlobalDefinitions() {
        Seed admin = seed(UserRole.GLOBAL_ADMIN, false);
        String session = login(admin, INITIAL_PASSWORD).then().statusCode(200)
                .extract().cookie(AuthResource.SESSION_COOKIE);
        String type = "AUDIT_" + UUID.randomUUID().toString().substring(0, 8).replace('-', '_').toUpperCase();

        Response global = given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, session)
                .body(workItemBody(null, true, type))
                .when().post("/api/v1/work-items/definitions");
        global.then().statusCode(200).body("globalScope", equalTo(true)).body("statuses.size()", equalTo(3));
        String globalId = global.jsonPath().getString("id");

        Map<String, Object> updatedGlobal = workItemBody(null, true, type);
        updatedGlobal.put("displayName", "Updated audit workflow");
        given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, session).body(updatedGlobal)
                .when().put("/api/v1/work-items/definitions/{id}", globalId)
                .then().statusCode(200).body("displayName", equalTo("Updated audit workflow"));

        String tenantCode = "GRAPH-" + UUID.randomUUID().toString().substring(0, 8);
        String tenantId = given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, session)
                .body(Map.of("companyCode", tenantCode, "displayName", "Graph tenant", "active", true))
                .when().post("/api/v1/tenants").then().statusCode(200).extract().path("id");

        Response override = given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, session)
                .body(workItemBody(tenantId, false, type))
                .when().post("/api/v1/work-items/definitions");
        override.then().statusCode(200).body("globalScope", equalTo(false)).body("overridesDefinitionId", equalTo(globalId));
        String overrideId = override.jsonPath().getString("id");

        given().cookie(AuthResource.SESSION_COOKIE, session).queryParam("tenantId", tenantId)
                .when().get("/api/v1/work-items/effective")
                .then().statusCode(200)
                .body("find { it.type == '" + type + "' }.id", equalTo(overrideId));

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
                .when().get("/api/v1/auth/me").then().statusCode(200).extract().path("tenantId");

        Response definition = given().cookie(AuthResource.SESSION_COOKIE, adminSession)
                .when().get("/api/v1/work-items/effective").then().statusCode(200)
                .extract().response();
        String definitionId = definition.jsonPath().getString("find { it.type == 'INCOME_TAX' }.id");
        String initialStatusId = definition.jsonPath().getString(
                "find { it.type == 'INCOME_TAX' }.statuses.find { it.initialStatus }.id");
        String startTransitionId = definition.jsonPath().getString(
                "find { it.type == 'INCOME_TAX' }.transitions.find { it.fromStatus == 'NEW' }.id");
        String completeTransitionId = definition.jsonPath().getString(
                "find { it.type == 'INCOME_TAX' }.transitions.find { it.fromStatus == 'IN_PROGRESS' }.id");

        String processorId = given().contentType(ContentType.JSON).cookie(AuthResource.SESSION_COOKIE, adminSession)
                .body(Map.of("companyCode", admin.companyCode(), "username", "workflow.processor",
                        "temporaryPassword", "WorkflowTemp-123", "role", "PROCESSOR"))
                .when().post("/api/v1/users").then().statusCode(200).extract().path("id");

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
                .body("items[0].currentStatus", equalTo("NEW"))
                .body("items[0].allowedTransitions[0].id", equalTo(startTransitionId));
        String executionId = myWork.jsonPath().getString("items[0].id");

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .when().post("/api/v1/work-items/executions/{executionId}/transitions/{transitionId}",
                        executionId, startTransitionId)
                .then().statusCode(200).body("currentStatus", equalTo("IN_PROGRESS"));

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .when().get("/api/v1/work-items/my-work")
                .then().statusCode(200)
                .body("items[0].allowedTransitions[0].id", equalTo(completeTransitionId))
                .body("items[0].activities[0].fromStatus", equalTo("NEW"))
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
                .body("items[0].id", equalTo(executionId))
                .body("items[0].terminal", equalTo(true));

        given().cookie(AuthResource.SESSION_COOKIE, processorSession)
                .when().get("/api/v1/work-items/executions/{executionId}", executionId)
                .then().statusCode(200)
                .body("execution.id", equalTo(executionId))
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
                .body(containsString("My work"))
                .body(containsString("Work item definitions"))
                .body(containsString("Workflow assignments"))
                .body(containsString("Work accounts"))
                .body(containsString("Include completed work"))
                .body(containsString("work-item-detail"))
                .body(containsString("/assets/casiq-logo.png"));

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
        body.put("statuses", List.of(
                Map.of("code", "NEW", "displayName", "New", "initialStatus", true, "terminalStatus", false, "sortOrder", 0),
                Map.of("code", "REVIEW", "displayName", "Review", "initialStatus", false, "terminalStatus", false, "sortOrder", 1),
                Map.of("code", "DONE", "displayName", "Done", "initialStatus", false, "terminalStatus", true, "sortOrder", 2)));
        body.put("transitions", List.of(
                Map.of("fromStatus", "NEW", "toStatus", "REVIEW", "label", "Review"),
                Map.of("fromStatus", "REVIEW", "toStatus", "DONE", "label", "Complete")));
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
