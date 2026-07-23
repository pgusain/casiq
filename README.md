# Casiq

Casiq is a Java 17 / Quarkus multi-module SaaS application. It produces one
Quarkus application on port `8080`, composed from tenant-aware user management and
the Gmail account connector.

## Modules

| Module | Responsibility |
| --- | --- |
| `casiq` | Top-level Maven aggregator, dependency management, and root Flyway configuration |
| `casiq-application` | The only runnable Quarkus application; assembles all feature modules and owns runtime configuration |
| `user-management` | Pluggable backend library containing company login, roles, password management, sessions, and user APIs |
| `work-item-management` | Pluggable work-item definition library containing tenant inheritance, status graphs, validation, and APIs |
| `work-account-management` | Parent module for work-account connectors |
| `work-account-management/work-account-core` | Tenant-scoped work accounts, work-item mapping, connection metadata, and APIs |
| `work-account-management/gmail-account-connector` | Pluggable Gmail backend library containing PKCE, token exchange, and endpoints |
| `user-experience` | Pluggable root-level UI library containing the Gmail console and user-management screens |

The feature and UI modules have no Quarkus application plugin or independent
runtime. `casiq-application` packages them into one application on port `8080`.

## User-management deployment

The schema and initial administrator are installed by root-level Flyway migration
`V2__create_tenants_users_and_initial_admin.sql`. The runnable application packages
the root migrations and automatically applies pending migrations during startup,
including table creation on a new database. Set these values before the first run:

```bash
cp .env.example .env

# Generate a bcrypt hash without storing the temporary plaintext password in Git.
# htpasswd is supplied by Apache HTTP Server tools.
htpasswd -bnBC 12 "" 'choose-a-temporary-password' | tr -d ':\n'
```

Copy the resulting hash into `INITIAL_ADMIN_PASSWORD_HASH` in `.env`, then set the
database connection, company code, and username. Migrations run automatically when
the application starts. The root Maven commands remain available for inspection or
manual deployment workflows:

```bash
set -a && source .env && set +a
mvn -N flyway:info
mvn -N flyway:migrate
mvn -N flyway:validate
```

No default administrator password is committed. Flyway creates the configured
account as `GLOBAL_ADMIN` with `must_change_password = true`; the user must replace
the temporary deployment password immediately after the first login.

Build the library modules once, then start the single application:

```bash
set -a && source .env && set +a
mvn install -DskipTests
mvn -f casiq-application/pom.xml quarkus:dev
```

Open <http://localhost:8080/> for login and user administration, or
<http://localhost:8080/gmail/> for the Gmail connector console. For local HTTP, keep
`SESSION_COOKIE_SECURE=false`; use the default secure cookie setting in an HTTPS
deployment.

### Identity and authorization

Login is uniquely resolved by normalized `company code + username`; usernames can
therefore be reused by different tenants. Passwords are bcrypt hashes and opaque
session tokens are stored only as SHA-256 hashes.

Roles are:

- `GLOBAL_ADMIN`: manages users across tenants and exclusively lists, creates,
  activates/deactivates, or updates tenants.
- `ADMIN`: lists its tenant and manages `PROCESSOR` and `BASE_USER` accounts in it.
- `PROCESSOR`: authenticated application user without user-administration access.
- `BASE_USER`: authenticated application user without user-administration access.

An administrator reset sets a new temporary bcrypt password, marks the target for
mandatory password replacement, and revokes all of that target user's sessions.
Administrators change their own passwords through the normal current-user flow.

### User-management API

| Method and path | Purpose |
| --- | --- |
| `POST /api/v1/auth/login` | Login with company code, username, and password |
| `GET /api/v1/auth/me` | Read the current session user |
| `POST /api/v1/auth/password` | First-login or current-user password change |
| `POST /api/v1/auth/logout` | Revoke the current session |
| `GET /api/v1/users` | List users in the administrator's allowed scope |
| `POST /api/v1/users` | Create a user with a temporary password |
| `POST /api/v1/users/{id}/reset-password` | Reset another user's password |
| `GET /api/v1/tenants` | List tenants (`GLOBAL_ADMIN` only) |
| `POST /api/v1/tenants` | Create a tenant (`GLOBAL_ADMIN` only) |
| `PUT /api/v1/tenants/{id}` | Update a tenant (`GLOBAL_ADMIN` only) |
| `GET /api/v1/work-items/definitions` | List all global definitions and tenant overrides (`GLOBAL_ADMIN` only) |
| `POST /api/v1/work-items/definitions` | Create a CASIQ-wide definition or tenant override (`GLOBAL_ADMIN` only) |
| `PUT /api/v1/work-items/definitions/{id}` | Update a definition's display settings and status graph (`GLOBAL_ADMIN` only) |
| `GET /api/v1/work-items/effective?tenantId={id}` | Resolve active global definitions with tenant overrides |
| `GET /api/v1/work-items/assignments?tenantId={id}` | List tenant status and transition assignments (`GLOBAL_ADMIN`/`ADMIN`) |
| `POST /api/v1/work-items/assignments` | Assign a status or transition to a user in the same tenant |
| `DELETE /api/v1/work-items/assignments/{type}/{id}` | Remove a status or transition assignment |
| `GET /api/v1/work-items/my-work` | Page and sort assigned non-terminal work, with type, status, email, and completed-work filters |
| `GET /api/v1/work-items/executions/{id}` | Open an assigned work item with its linked email content and activity history |
| `POST /api/v1/work-items/executions/{id}/transitions/{transitionId}` | Perform an assigned transition |
| `GET /api/v1/work-accounts` | List work accounts in the administrator's tenant scope |
| `POST /api/v1/work-accounts` | Add an email work account mapped to an effective work-item definition |
| `PUT /api/v1/work-accounts/{id}` | Edit an email or work-item mapping |
| `GET /api/v1/work-accounts/providers` | List active email providers from reference data |
| `POST /api/v1/work-accounts/{id}/authorize` | Start the connector flow selected by the work account provider |

Swagger UI is available at <http://localhost:8080/q/swagger-ui> while the service
is running.

## Work-item graphs

Flyway migration `V5__create_work_item_graphs.sql` creates definition, status-node,
and directed-transition tables and migrates existing work accounts to definition
foreign keys. It also provides CASIQ-wide `INCOME_TAX` and `GST` starter graphs.

A CASIQ-wide definition is available to every tenant. A tenant definition with the
same type is an override and shadows the CASIQ-wide version only for that tenant.
Exactly one initial status is required; status codes must be unique, transitions
must reference existing nodes, terminal nodes cannot have outgoing transitions,
and every node must be reachable from the initial node. The authenticated dashboard
contains the global-admin graph editor and uses effective definitions in the
work-account dropdown.

Flyway migration `V6__create_work_item_executions_and_assignments.sql` gives each
work account an independent current workflow status. Tenant administrators can
assign an entire status or one specific transition to an active user in the same
tenant. Status ownership permits every outgoing transition from that status;
transition ownership permits only the assigned activity. Every completed transition
is recorded with its performer, previous status, next status, and timestamp. The
dashboard exposes assignment management to administrators and a tenant-isolated
**My work** queue to all authenticated roles.

## Gmail connector

Create an OAuth 2.0 **Web application** credential in Google Cloud and register:

```text
http://localhost:8080/api/v1/gmail/callback
```

Set the three `GOOGLE_*` values in `.env` before starting the same application.
The Gmail page at <http://localhost:8080/gmail/> is the OAuth test console. It requests
`openid`, `email`, `profile`, and Gmail read-only permission, with offline consent
so Google can return a refresh token. OAuth state and PKCE verifiers currently live
in memory for ten minutes; returned tokens are shown only in the test page.

The main authenticated UI also connects Google directly to a work account. That flow
uses Gmail `users.getProfile("me")` to verify the selected Google mailbox matches the
configured email, then stores the access token, refresh token, and access-token expiry
server-side. Work-account API responses expose connection status and expiry, never the
stored token values. Changing a work account's email clears its prior connection.

Flyway migration `V7__add_email_providers_and_polling_config.sql` provides the
`GOOGLE` and `MICROSOFT` reference values. The durable `work_account` row stores
the email, provider, and refresh token; short-lived access tokens are stored in the
one-to-one `email_polling_config` row with their expiry and next-refresh timestamp.
Changing the email or provider clears both credential states. Google routes to the
Gmail OAuth connector; Microsoft is available as reference data and returns a clear
not-configured response until its connector is implemented.

### Incremental email polling

Flyway migration `V8__add_email_polling_leases_and_conversations.sql` adds polling
cursors and expiring leases to `email_polling_config`, plus the
`work_account_conversation` table. Each scheduler pass atomically claims at most
`EMAIL_POLLING_BATCH_SIZE` due configurations using `FOR UPDATE SKIP LOCKED` and
sets a lease for `EMAIL_POLLING_LOCK_SECONDS`. This prevents another application
instance from claiming the same mailbox during that window.

The scheduler, leasing, worker pool, provider dispatch, and conversation persistence
live in `work-account-core`. Claimed records are submitted to a dedicated pool of
10 worker threads, and the configured provider code selects an
`EmailProviderConnector` implementation. The Gmail module implements that contract
for `GOOGLE`; it refreshes expired access tokens and returns messages newer than
`last_polled_at` to the core workflow. The unique
`work_account_id + provider_message_id` constraint makes repeated delivery
idempotent. Successful polls advance `last_polled_at` and `next_refresh_at`;
failures release the lease, record the error, and schedule a configured retry.
Polling limits, intervals, and initial lookback are configured through the
`EMAIL_POLLING_*` values; Gmail's page size uses `GMAIL_POLLING_PAGE_SIZE` in
`.env.example`.

Flyway migration `V9__add_conversation_reply_metadata.sql` adds the RFC
`Message-ID`, `In-Reply-To`, and `References` headers as dedicated conversation
columns. It also records each message as `INBOUND` or `OUTBOUND`, allowing replies
to be linked to the provider thread without parsing the stored JSON payload.

### Conversation work-item creation

The `work-item-management` module owns a separate scheduler that converts each
unprocessed inbound conversation into a work-item execution. It snapshots the work
account email, uses the definition currently linked to that account, and starts the
execution at the definition's initial status. The execution has a unique
`conversation_id`, providing database-level idempotency.

Flyway migration `V10__create_conversation_work_item_pipeline.sql` adds processing,
lease, retry, and error state to `work_account_conversation`. Each scheduler pass
claims at most `CONVERSATION_WORK_ITEM_BATCH_SIZE` records with
`FOR UPDATE SKIP LOCKED`, applies an expiring lease, and submits them to a dedicated
10-thread worker pool. Failed records are released and delayed until the configured
retry time. Scheduler interval, batch size, lock duration, and retry delay use the
`CONVERSATION_WORK_ITEM_*` settings in `.env.example`.

The My Work screen excludes terminal statuses by default. Users can filter by
work-item type, current status, or work-account email prefix and can explicitly include
completed work. Results support page sizes from 1 to 100 and sorting by last
update, creation time, email, work-item type, or status in either direction.
Opening a work item displays its linked email and provides the currently assigned
transitions; the queue card also provides quick transition shortcuts. Gmail
plain-text and HTML bodies are normalized to a safe HTML representation
into columns added by `V11__add_conversation_rendered_content.sql`; HTML is rendered
inside a sandboxed, network-blocked frame.

Flyway migration `V12__optimize_polling_and_work_item_queries.sql` adds composite
indexes for tenant work queues, definition/status filters, normalized work-account
email lookup, initial-status resolution, assignments, and activity ownership. It
also stores a normalized execution email so filtering does not require a database
function on every row.

The email-polling and conversation-to-work-item schedulers, claim services,
workers, provider connector, and workflow service emit structured lifecycle,
batch, success, retry, failure, filter, and transition logs. INFO is the default;
set `CASIQ_LOG_LEVEL=DEBUG` to include lease decisions, empty scheduler passes,
provider pagination, and worker boundaries. Credentials and email bodies are not
written to logs.

The root POM and feature modules are not runnable applications. Only
`casiq-application` is started with `quarkus:dev`.

## Build and test

```bash
mvn verify

# One native executable containing all configured feature modules
mvn -pl casiq-application -am package -Dnative -DskipTests
```
