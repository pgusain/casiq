# Casiq

Casiq is a Java 17 / Quarkus multi-module SaaS application. It produces one
Quarkus application on port `8080`, composed from tenant-aware user management and
Google Gmail and Microsoft 365 email connectors.

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
| `work-account-management/microsoft-account-connector` | Pluggable Microsoft 365 backend library containing Entra PKCE authorization and Microsoft Graph mailbox operations |
| `user-experience` | Pluggable root-level UI library containing the Gmail console and user-management screens |

The feature and UI modules have no Quarkus application plugin or independent
runtime. `casiq-application` packages them into one application on port `8080`.

## User-management deployment

The complete schema, reference data, starter workflows, and initial administrator
are installed by the consolidated root-level Flyway migration
`V1__baseline.sql`. It is intended for a new, empty database. The runnable
application packages and automatically applies it during startup. Generate the
initial administrator's BCrypt hash before the first run:

```bash
# Generate a bcrypt hash without storing the temporary plaintext password in Git.
# htpasswd is supplied by Apache HTTP Server tools.
htpasswd -bnBC 12 "" 'choose-a-temporary-password' | tr -d ':\n'
```

Store the hash in the active environment's SSM
`initial-admin-password-hash` parameter, then set that path's database connection,
company code, and username parameters. Migrations run automatically when the
application starts.

For the consolidated baseline, recreate the entire database (including removal of
the old `flyway_schema_history` table) before starting the application. Do not run
Flyway repair against a database that contains an earlier development version of
`V1`; the current baseline deliberately represents the complete fresh-install
schema and seed state.

No default administrator password is committed. Flyway creates the configured
account as `GLOBAL_ADMIN` with `must_change_password = true`; the user must replace
the temporary deployment password immediately after the first login.

Build the library modules once, then start the single application with the
development profile. Your local AWS credentials must be able to read
`/casiq/development/` from SSM in `ap-south-1`:

```bash
mvn install -DskipTests
mvn -f casiq-application/pom.xml quarkus:dev -Dquarkus.profile=development
```

Open <http://localhost:8080/> for login and user administration, or
<http://localhost:8080/gmail/> for the Gmail connector console. For local HTTP,
set `casiq.security.cookie-secure=false` in the development profile; keep it true
for an HTTPS deployment.

## EC2 promotion workflow

`.github/workflows/promote.yml` tests every push to `main`, publishes an immutable
`${GITHUB_SHA}` image to GitHub Container Registry, deploys it to the
`development` GitHub Environment, and then promotes that exact image to the
`production` Environment. Configure a required reviewer on `production` to make
promotion an explicit approval gate.

Shared application settings (ports, batch sizes, polling intervals, and so on)
are literal values in `application.properties`. The `development` and
`production` profiles enable the Quarkus SSM config source and read separate
paths in the same AWS account and `ap-south-1` region. The application decrypts
parameters itself at startup; the deploy workflow does not copy them into
environment variables or a temporary file.

Create each suffix below under both `/casiq/development/` and
`/casiq/production/` (for example `/casiq/development/db-password` and
`/casiq/production/db-password`):

| Parameter suffix | Recommended SSM type |
| --- | --- |
| `db-url` | `String` |
| `db-username` | `String` |
| `db-password` | `SecureString` |
| `initial-admin-company-code` | `String` |
| `initial-admin-username` | `String` |
| `initial-admin-password-hash` | `SecureString` |
| `google-client-id` | `String` |
| `google-client-secret` | `SecureString` |
| `google-redirect-uri` | `String` |
| `microsoft-client-id` | `String` |
| `microsoft-client-secret` | `SecureString` |
| `microsoft-tenant` | `String` |
| `microsoft-redirect-uri` | `String` |

Quarkus selects the profile-specific file from `QUARKUS_PROFILE`, which the
workflow defaults to `development` or `production` for the corresponding job.

Create both GitHub Environments with these environment variables:

| Variable | Purpose |
| --- | --- |
| `EC2_HOST` | EC2 hostname or IP |
| `EC2_USER` | SSH user with permission to run Docker |
| `EC2_SSH_PORT` | SSH port; defaults to `22` |
| `EC2_HOST_FINGERPRINT` | SHA-256 SSH host fingerprint |
| `QUARKUS_PROFILE` | Quarkus profile to activate; defaults to `development` / `production` per job |
| `APP_URL` | Public URL recorded on the GitHub deployment |
| `APP_PORT` | Host port mapped to container port `8080` |
| `APP_BIND_ADDRESS` | Host bind address; defaults to `127.0.0.1` for a local reverse proxy |
| `ATTACHMENT_DATA_DIR` | Persistent host attachment directory |
| `CONTAINER_NAME` | Environment-specific Docker container name |

Add `EC2_SSH_KEY` as an environment secret in both environments. No AWS
credentials are passed through the workflow or the SSH session: each EC2 host
must carry an instance profile authorizing `ssm:GetParametersByPath` on only
its environment path (and `kms:Decrypt` if `SecureString` parameters use a
customer-managed key). The container must be able to reach EC2 instance
metadata; when IMDSv2 is required, configure the instance metadata response hop
limit to allow container access. Each host needs Docker and `curl` and must be
able to pull this repository's GHCR package. The deploy script keeps attachment
storage on the host and rolls back to the previous image if the new container
does not answer its HTTP health check.

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
| `PUT /api/v1/work-items/definitions/{id}` | Update a definition's display settings and transition graph (`GLOBAL_ADMIN` only) |
| `GET /api/v1/work-items/effective?tenantId={id}` | Resolve active global definitions with tenant overrides |
| `GET /api/v1/work-items/assignments?tenantId={id}` | List tenant status and transition assignments (`GLOBAL_ADMIN`/`ADMIN`) |
| `POST /api/v1/work-items/assignments` | Assign a status or transition to a user in the same tenant |
| `DELETE /api/v1/work-items/assignments/{type}/{id}` | Remove a status or transition assignment |
| `GET /api/v1/work-items/my-work` | Page and sort work with `MY`, `OTHER`, or `ALL` queue scope plus type, status, email, and terminal filters |
| `GET /api/v1/work-items/my-work/status-summary` | Count all accessible work items by status for the current filters |
| `GET /api/v1/work-items/executions/{id}` | Open an assigned work item with its complete inbound/outbound communication timeline |
| `POST /api/v1/work-items/executions/{id}/pick?force={boolean}` | Atomically assign a work item to the current user or explicitly take it over |
| `PUT /api/v1/work-items/executions/{id}/type` | Change an actionable work item's effective type while preserving its current status |
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
directed-transition tables. Every definition automatically receives
`AWAITING_FIRST_RESPONSE`, `READY_TO_PICK`, `IN_PROGRESS`,
`AWAITING_CUSTOMER_RESPONSE`, `CANCELLED`, and `COMPLETED`.
`AWAITING_FIRST_RESPONSE` is initial, while
`CANCELLED` and `COMPLETED` are terminal. Administrators define the directed
transitions between these fixed statuses. CASIQ-wide `INCOME_TAX` and `GST`
starter graphs are provided.

A CASIQ-wide definition is available to every tenant. A tenant definition with the
same type is an override and shadows the CASIQ-wide version only for that tenant.
Transitions must reference the fixed nodes, terminal nodes cannot have outgoing
transitions, and every node must be reachable from the initial node. The authenticated dashboard
contains the global-admin graph editor and uses effective definitions in the
work-account dropdown.

Work-item execution and assignment tables give each work account an independent
current workflow status. Tenant administrators can
assign an entire status or one specific transition to an active user in the same
tenant. Status ownership permits every outgoing transition from that status;
transition ownership permits only the assigned activity. Every completed transition
is recorded with its performer, previous status, next status, and timestamp. The
dashboard exposes assignment management to administrators and tenant-isolated
**My tasks** and **Other tasks** queues to all authenticated roles.

## Gmail connector

Create an OAuth 2.0 **Web application** credential in Google Cloud and register:

```text
http://localhost:8080/api/v1/gmail/callback
```

Set the active profile's `google-client-id`, `google-client-secret`, and
`google-redirect-uri` SSM parameters before starting the application.
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

## Microsoft 365 connector

Create a Microsoft Entra **Web** app registration and register:

```text
http://localhost:8080/api/v1/microsoft/callback
```

Grant delegated `User.Read`, `Mail.ReadWrite`, and `Mail.Send` permissions. The
connector also requests `openid`, `profile`, `email`, and `offline_access` during
authorization. Set the active profile's `microsoft-client-id`,
`microsoft-client-secret`, `microsoft-tenant`, and `microsoft-redirect-uri` SSM
parameters. Use `organizations` for the tenant unless the app registration must
be single-tenant.

Microsoft 365 work accounts use Microsoft Graph to verify the selected mailbox,
rotate refresh tokens, page the Inbox incrementally, fetch HTML and file
attachments, and create threaded MIME replies. Graph requests opt into immutable
Outlook IDs so stored provider message IDs remain valid when reply drafts move to
Sent Items.

The baseline provides the `GOOGLE` and `MICROSOFT` reference values. The durable
`work_account` row stores
the email, provider, and refresh token; short-lived access tokens are stored in the
one-to-one `email_polling_config` row with their expiry and next-refresh timestamp.
Changing the email or provider clears both credential states. The provider code
routes authorization, polling, direct reads, replies, and conversation matching to
the corresponding connector.

### Incremental email polling

The baseline includes polling cursors and expiring leases in
`email_polling_config`, plus the
`work_account_conversation` table. Each scheduler pass atomically claims at most
the configured `casiq.email-polling.batch-size` due configurations using
`FOR UPDATE SKIP LOCKED` and sets a lease for
`casiq.email-polling.lock-seconds`. This prevents another application
instance from claiming the same mailbox during that window.

The scheduler, leasing, worker pool, provider dispatch, and conversation persistence
live in `work-account-core`. Claimed records are submitted to a dedicated pool of
10 worker threads, and the configured provider code selects an
`EmailProviderConnector` implementation. The Gmail and Microsoft 365 modules
implement that contract for `GOOGLE` and `MICROSOFT`; each refreshes expired access
tokens and returns messages newer than `last_polled_at` to the core workflow. The unique
`work_account_id + provider_message_id` constraint makes repeated delivery
idempotent. Successful polls advance `last_polled_at` and `next_refresh_at`;
failures release the lease, record the error, and schedule a configured retry.
Polling limits, intervals, initial lookback, provider page sizes, and attachment
limits are literal `casiq.email-polling.*`, `casiq.gmail.*`, and
`casiq.microsoft.*` values in `application.properties`. Attachments are downloaded
through the provider connector and stored with the source conversation; the default
25 MiB per-attachment limits prevent one message from consuming unbounded worker
memory.

The conversation schema stores the RFC `Message-ID`, `In-Reply-To`, and
`References` headers as dedicated conversation
columns. It also records each message as `INBOUND` or `OUTBOUND`, allowing replies
to be linked to the provider thread without parsing the stored JSON payload.
Conversation-to-work-item matching is also provider-owned: Gmail matches Gmail
thread IDs, while Microsoft 365 first matches Outlook conversation IDs and then
falls back to RFC reply/reference headers. The work-item module exposes only
tenant- and provider-scoped lookups and does not encode provider semantics.

### Conversation work-item creation

The `work-item-management` module owns a separate scheduler that converts each
unprocessed inbound conversation into a work-item execution. It snapshots the work
account email, uses the definition currently linked to that account, and starts the
execution at the definition's initial status. The execution has a unique
`conversation_id`, providing database-level idempotency.

Processing, lease, retry, and error state are part of
`work_account_conversation`. Each scheduler pass
claims at most `casiq.conversation-work-item.batch-size` records with
`FOR UPDATE SKIP LOCKED`, applies an expiring lease, and submits them to a dedicated
10-thread worker pool. Failed records are released and delayed until the configured
retry time. Scheduler interval, batch size, lock duration, and retry delay use the
`casiq.conversation-work-item.*` settings in `application.properties`.

The work-item screen only lists non-terminal tasks for which the current user has
a status or transition assignment applicable to the task's current status. It
separates those actionable tasks into items assigned to the current user and
unassigned or other-owned items. Opening an unassigned task atomically assigns it to the
current user. If another user already owns it, the UI names that user and offers
an explicit takeover or read-only view. Picks, forced takeovers, type changes,
transitions, internal notes, document uploads, and outbound replies use the
execution's optimistic version before checking or changing ownership. Concurrent
stale updates return HTTP 409 so the caller can refresh and retry. A forced
takeover replaces the current assignee even when the task is already owned.
An actionable, non-terminal work item can also change type from its detail view.
The current fixed status and assignee are retained, while allowed transitions and
queue visibility are immediately recalculated from the selected type's tenant
assignments. This changes only that execution; the work account's default type is
not modified.
The screen excludes terminal statuses by default. Users can filter by
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
work item instead of creating a duplicate. When that work item is awaiting a
customer response, the inbound processor pessimistically locks it and automatically
moves it to `READY_TO_PICK` without changing its assignee. Work-item documents are classified as
`INBOUND`, `INTERNAL`, or `OUTBOUND`; the UI groups them by that origin. Inbound
and outbound documents retain their source conversation ID, so every email in the
timeline displays its corresponding downloadable attachments. The document side
sheet also identifies the linked email subject. Internal uploads remain
work-item-level documents without an email link. Inbound email attachments and
internal uploads are stored through the `attachment-storage` module, while
outbound document rows record exactly which files were sent.

Local attachment storage uses `casiq.attachment-storage.provider=local` and stores
files below `casiq.attachment-storage.local-root`, partitioned by tenant ID. Set the
provider to `s3` in the applicable profile when needed. S3 mode derives a separate
bucket name for every tenant as `<casiq.attachment-storage.s3-bucket-prefix><tenant
ID>`. Provision those buckets before use and grant the application `s3:PutObject`
and `s3:GetObject` only for the applicable tenant buckets. The S3 client uses the
configured `ap-south-1` region and the AWS SDK default credential provider chain;
static cloud credentials are not stored in Casiq configuration.

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

The retention scheduler can delete processed materialized conversations after
`casiq.conversation-retention.hours` (180 days by default). It is disabled by default
and, when enabled, operates in bounded batches using
database row locks and `SKIP LOCKED` so multiple application instances can run it
safely. Cached bodies are cleared after
`casiq.conversation-retention.cache-fallback-hours`.
Provider message IDs remain in the durable ledger, preventing an old message from
being ingested again after its materialized row has been purged. New messages on
an existing provider thread continue to attach to the original work item.
Set `casiq.work-item.provider-read-enabled=false` to use metadata/cache only. Set
`casiq.conversation-retention.enabled=true` only when automatic conversation cleanup
should be enabled.

### Completed work-item archive

The work-item module can run a weekly archive pass at 02:00 UTC every Sunday.
It is disabled by default and claims only `COMPLETED` executions in bounded batches using
`FOR UPDATE SKIP LOCKED` and an expiring lease, so multiple application
instances cannot archive the same execution concurrently. Failed uploads release
the lease and are retried after the configured delay.

Each archive is one consolidated JSON object containing the execution snapshot,
status activity, inbound and outbound communication timeline, internal notes,
document metadata, and document content. The object key is
`work-items/<execution ID>.json` inside the tenant's configured storage. In S3
mode this is the tenant-specific bucket described above; local development uses
the same logical layout below `casiq.attachment-storage.local-root`.

Detail rows are purged only after the JSON write succeeds. The primary
`work_item_execution` row remains available for tenant search and list views and
is marked with `data_migrated`, the archive provider/key, and archive timestamp.
Opening an archived execution transparently loads its read-only details and
document downloads from the JSON object. `CANCELLED` and other non-completed
executions are not archived.

The schedule, time zone, claim size, lease, retry delay, and worker count use the
`casiq.work-item-archive.*` settings in `application.properties`. Enable
`casiq.work-item-archive.enabled` only when completed work-item archival and detail
purging should be enabled.

The consolidated baseline includes composite indexes for tenant work queues,
definition/status filters, normalized work-account
email lookup, initial-status resolution, assignments, and activity ownership. It
also stores a normalized execution email so filtering does not require a database
function on every row.

The email-polling and conversation-to-work-item schedulers, claim services,
workers, provider connector, and workflow service emit structured lifecycle,
batch, success, retry, failure, filter, and transition logs. INFO is the default;
set `quarkus.log.category."com.casiq".level=DEBUG` to include lease decisions, empty scheduler passes,
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
