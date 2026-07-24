# Casiq

Casiq is a Java 17 / Quarkus multi-module SaaS application. It produces one
Quarkus application on port `8080`, composed from tenant-aware user management and
the Gmail account connector.

## Modules

| Module | Responsibility |
| --- | --- |
| `casiq` | Top-level Maven aggregator, dependency management, and root Flyway configuration |
| `attachment-storage` | Pluggable tenant-scoped attachment storage with local-disk and Amazon S3 implementations |
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

The complete schema, reference data, starter workflows, and initial administrator
are installed by the consolidated root-level Flyway migration
`V1__baseline.sql`. It is intended for a new, empty database. The runnable
application packages and automatically applies it during startup. Set these values
before the first run:

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
The user editor updates username, first name, last name, role, and active status
within the administrator's tenant scope. Login-affecting changes revoke the
target user's sessions. Administrators cannot change their own role or deactivate
themselves, and the application preserves at least one active `GLOBAL_ADMIN`.

### User-management API

| Method and path | Purpose |
| --- | --- |
| `POST /api/v1/auth/login` | Login with company code, username, and password |
| `GET /api/v1/auth/me` | Read the current session user |
| `POST /api/v1/auth/password` | First-login or current-user password change |
| `POST /api/v1/auth/logout` | Revoke the current session |
| `GET /api/v1/users` | List users in the administrator's allowed scope |
| `POST /api/v1/users` | Create a user with a temporary password |
| `PUT /api/v1/users/{id}` | Edit a user's identity, role, and active status |
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
| `GET /api/v1/work-items/my-work/status-summary` | Count all accessible work items by status for the current filters |
| `GET /api/v1/work-items/executions/{id}` | Open an assigned work item with its complete inbound/outbound communication timeline |
| `POST /api/v1/work-items/executions/{id}/notes` | Add a tenant-internal note to an assigned work item |
| `POST /api/v1/work-items/executions/{id}/documents` | Upload an internal-team document as multipart form data |
| `GET /api/v1/work-items/executions/{id}/documents/{documentId}` | Download an authorized work-item attachment |
| `POST /api/v1/work-items/executions/{id}/transitions/{transitionId}` | Perform an assigned transition |
| `POST /api/v1/work-item-replies/{id}` | Reply to the original sender through the work account provider |
| `GET /api/v1/work-accounts` | List work accounts in the administrator's tenant scope |
| `POST /api/v1/work-accounts` | Add an email work account mapped to an effective work-item definition |
| `PUT /api/v1/work-accounts/{id}` | Edit an email or work-item mapping |
| `GET /api/v1/work-accounts/providers` | List active email providers from reference data |
| `POST /api/v1/work-accounts/{id}/authorize` | Start the connector flow selected by the work account provider |

Swagger UI is available at <http://localhost:8080/q/swagger-ui> while the service
is running.

## Work-item graphs

The consolidated baseline creates definition, status-node, and
directed-transition tables. It also provides CASIQ-wide `INCOME_TAX` and `GST`
starter graphs.

A CASIQ-wide definition is available to every tenant. A tenant definition with the
same type is an override and shadows the CASIQ-wide version only for that tenant.
Exactly one initial status is required; status codes must be unique, transitions
must reference existing nodes, terminal nodes cannot have outgoing transitions,
and every node must be reachable from the initial node. The authenticated dashboard
contains the global-admin graph editor and uses effective definitions in the
work-account dropdown.

Work-item execution and assignment tables give each work account an independent
current workflow status. Tenant administrators can
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
`openid`, `email`, `profile`, Gmail read-only, and Gmail send permission, with offline
consent so Google can return a refresh token. Existing Google work accounts must be
reconnected once to grant the send scope. OAuth state and PKCE verifiers currently
live in memory for ten minutes; returned tokens are shown only in the test page.

The main authenticated UI also connects Google directly to a work account. That flow
uses Gmail `users.getProfile("me")` to verify the selected Google mailbox matches the
configured email, then stores the access token, refresh token, and access-token expiry
server-side. Work-account API responses expose connection status and expiry, never the
stored token values. Changing a work account's email clears its prior connection.

The baseline provides the `GOOGLE` and `MICROSOFT` reference values. The durable
`work_account` row stores
the email, provider, and refresh token; short-lived access tokens are stored in the
one-to-one `email_polling_config` row with their expiry and next-refresh timestamp.
Changing the email or provider clears both credential states. Google routes to the
Gmail OAuth connector; Microsoft is available as reference data and returns a clear
not-configured response until its connector is implemented.

### Incremental email polling

The baseline includes polling cursors and expiring leases in
`email_polling_config`, plus the
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
`.env.example`. Gmail attachments are downloaded through the provider connector and
stored with the source conversation. `GMAIL_MAX_ATTACHMENT_BYTES` bounds each
attachment (25 MiB by default) so one message cannot consume unbounded worker memory.

The conversation schema stores the RFC `Message-ID`, `In-Reply-To`, and
`References` headers as dedicated conversation
columns. It also records each message as `INBOUND` or `OUTBOUND`, allowing replies
to be linked to the provider thread without parsing the stored JSON payload.

### Conversation work-item creation

The `work-item-management` module owns a separate scheduler that converts each
unprocessed inbound conversation into a work-item execution. It snapshots the work
account email, uses the definition currently linked to that account, and starts the
execution at the definition's initial status. The execution has a unique
`conversation_id`, providing database-level idempotency.

Processing, lease, retry, and error state are part of
`work_account_conversation`. Each scheduler pass
claims at most `CONVERSATION_WORK_ITEM_BATCH_SIZE` records with
`FOR UPDATE SKIP LOCKED`, applies an expiring lease, and submits them to a dedicated
10-thread worker pool. Failed records are released and delayed until the configured
retry time. Scheduler interval, batch size, lock duration, and retry delay use the
`CONVERSATION_WORK_ITEM_*` settings in `.env.example`.

The My Work screen excludes terminal statuses by default. Users can filter by
work-item type, current status, or work-account email prefix and can explicitly include
completed work. Results support page sizes from 1 to 100 and sorting by last
update, creation time, email, work-item type, or status in either direction.
Work items are displayed on a dedicated screen as a row-based list. Its filter
panel is collapsible, and status summary tiles count the complete accessible
queue rather than only the current page; selecting a tile applies that status.
Each execution has a tenant-scoped numeric work-item number. Numbering starts at
`100000` independently for each tenant. The list shows that number and a
15-character email-subject preview; the number is also shown when the work item
is opened.
Opening a work item displays its linked email and provides the currently assigned
transitions; the queue card also provides quick transition shortcuts. Gmail
plain-text and HTML bodies are normalized to a safe HTML representation in the
conversation schema; HTML is rendered inside a sandboxed, network-blocked frame.

The durable work-item communication ledger retains provider and addressing
metadata without permanently copying the message body onto the execution. Email
attachments are copied to tenant-scoped work-item documents,
so authorized users can list and download them from the document sidebar. Work items
also support multiple ordered internal notes with author and timestamp metadata.

The same detail dialog contains a formatting toolbar and HTML reply editor. Users
can select existing work-item documents or upload new files directly to attach
to the reply. A reply
is routed through the work account's `EmailProviderConnector`; Gmail creates a raw
multipart MIME message with the original `Message-ID`, `References`, and provider
thread ID. Sent messages are persisted as `OUTBOUND` conversations and appear in
the work-item communication timeline. Outbound attachments are recorded as
`OUTBOUND` work-item documents and appear in the document side panel. The
communication timeline is collapsed by default except for its newest message.
Each request supplies
an `outbound_request_id`, and a pessimistic per-account lock plus the unique request
constraint makes client retries idempotent.

Attachment filenames and MIME types use provider-safe lengths. Gmail's opaque
attachment IDs are converted to deterministic SHA-256 storage keys before
persistence, preventing provider-controlled identifiers from exceeding indexed
database column limits while preserving attachment idempotency.

Every inbound or outbound conversation is linked to its work-item execution. A
later inbound
email with the same provider thread ID and work account is attached to the existing
work item instead of creating a duplicate. Work-item documents are classified as
`INBOUND`, `INTERNAL`, or `OUTBOUND`; the UI groups them by that origin. Inbound
and outbound documents retain their source conversation ID, so every email in the
timeline displays its corresponding downloadable attachments. The document side
sheet also identifies the linked email subject. Internal uploads remain
work-item-level documents without an email link. Inbound email attachments and
internal uploads are stored through the `attachment-storage` module, while
outbound document rows record exactly which files were sent.

Local development defaults to `ATTACHMENT_STORAGE_PROVIDER=local` and stores files
below `ATTACHMENT_LOCAL_ROOT`, partitioned by tenant ID. For production, build and
run with `ATTACHMENT_STORAGE_PROVIDER=s3`. S3 mode derives a separate bucket name
for every tenant as `<ATTACHMENT_S3_BUCKET_PREFIX><tenant UUID>`. Provision those
buckets before use and grant the application `s3:PutObject` and `s3:GetObject`
only for the applicable tenant buckets. The S3 client reads `AWS_REGION` and uses
the AWS SDK default credential provider chain; static cloud credentials are not
stored in Casiq configuration.

Application users include first and last names. Both values are required when
administrators create new users and are
returned by the authentication and user-management APIs for navigation, profiles,
assignments, and user lists.

Every work-item execution has a database-backed numeric identifier unique within
its tenant. New values are allocated numerically per tenant under a
database tenant-row lock, keeping allocation safe across concurrent application
instances.

The durable `work_item_communication` metadata ledger lets work items retain provider
message/thread identifiers, addressing metadata, timestamps, attachment links,
and reply headers without depending on the materialized
`work_account_conversation` row. Opening a work item reads the materialized body
from `work_account_conversation` first. If that row is no longer available, the
message is read directly from its configured provider. A short-lived body cache
is the final fallback; if both sources are temporarily unavailable, the most
recent cached body is returned as visibly marked stale content.
If a retained conversation exists but has no HTML body, Casiq downloads the full
provider message, hydrates `content_html`, and renders that HTML in the sandboxed
work-item communication frame.

The retention scheduler deletes processed materialized conversations after
`CONVERSATION_RETENTION_HOURS` (180 days by default), in bounded batches using
database row locks and `SKIP LOCKED` so multiple application instances can run it
safely. Cached bodies are cleared after `WORK_ITEM_CACHE_FALLBACK_HOURS`.
Provider message IDs remain in the durable ledger, preventing an old message from
being ingested again after its materialized row has been purged. New messages on
an existing provider thread continue to attach to the original work item.
Set `WORK_ITEM_PROVIDER_READ_ENABLED=false` to use metadata/cache only, or
`CONVERSATION_RETENTION_ENABLED=false` to disable automatic purging.

The consolidated baseline includes composite indexes for tenant work queues,
definition/status filters, normalized work-account
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
