# Agentknock Android application screen specification

Status: review draft

Last updated: 2026-08-28

## Purpose

This document defines the intended screen structure and the information each
screen must present. It is an information architecture and behavior
specification, not a visual design, implementation plan, or commitment to ship
every later product direction in the first release.

It specifies user-visible capabilities, terminology, navigation, lifecycle,
and security outcomes. Storage schemas, concurrency mechanisms, protocol
plumbing, and code organization belong in separate technical designs unless a
choice directly changes the user contract.

Named screens, sections, and actions describe the information architecture,
not a required widget hierarchy. Their visual grouping and presentation may
change as long as the terminology, available choices, consequences, and
navigation relationships remain clear.

The specification is grounded in:

- the Agentknock PRD;
- the current Android application and its persisted data;
- the current CLI, including pairing, secret listing and upload, execution,
  and unpairing;
- the relay's current connection, enrollment, and push-registration behavior;
  and
- current Android adaptive-layout, settings, notification, and Google Play
  Billing guidance.

Where these sources disagree, the disagreement is called out in **Open product
decisions** instead of being silently resolved.

## Product vocabulary

[Agentknock terminology](TERMINOLOGY.md) is authoritative for product and UI
language. In particular, the public requestable object is a *secret*, the
Android installation is a *device*, and the paired CLI installation is a
*client*. *Environment variable* is always written in full. Bold terms below
may identify exact UI labels; prose uses the capitalization rules in the
terminology document.

A secret is the reusable unit that a client requests. Secret names occupy one
flat, globally unique namespace; type is not part of a secret's identity. A
command composes capabilities by requesting multiple secrets, for example
`agentknock exec -s aws-read-only -s production-ssh -- command`. Secrets do
not contain or reference other secrets.

Each secret has one concrete, immutable type. An Environment variables secret
contains a cohesive set of fixed environment variables. An AWS temporary
credentials secret owns its generation configuration and provides a fixed,
publicly listed set of environment variables together with one lifetime. The
names are visible before use is requested; their generated values are not
stored environment variables. An SSH key secret owns one key and uses
SSH-specific delivery rather than pretending the key is an environment
variable. Changing a secret to another type means creating a new secret.

Sensitivity is an independent property of stored values; a useful environment
variable may be non-sensitive even though all stored values are encrypted.
Types with inherently confidential material enforce appropriate handling. Use
*credential* for authentication material such as an AWS credential set, not as
the structural name for every secret.

## Screen and field conventions

Each section identifies its **Surface** so that a named product concept is not
mistaken for a separate screen:

- A **destination screen** is directly reachable from primary navigation or
  Settings and owns a list or overview.
- A **detail screen** is opened for one durable object. Request and audit-event
  types are variants of their shared detail screen, not separate navigation
  destinations.
- An **editor screen** creates or changes one object. It may reuse a detail
  pane on larger windows, but remains a distinct navigation state with explicit
  save or cancel behavior.
- A **focused action screen** handles a consequential multi-step action such as
  Factory reset. It is not a normal destination or a transient dialog.
- An **embedded display** is a state, card, section, or marker shown inside
  another screen. It does not add a navigation destination.
- A **system surface** is owned by Android, such as notification permission or
  device authentication.
- A **behavior definition** specifies shared behavior and has no surface of its
  own.

Data priority is described from a compact-phone baseline:

- **Primary** data is visible without expanding anything and identifies the
  object, action, or state.
- **Supporting** data is visible when it materially helps the immediate
  decision. Lists should normally have one primary identity, one status, and no
  more than two compact supporting facts; the detail screen owns the rest.
- **Technical details** remain available on the same detail screen through a
  secondary disclosure. Identifiers and protocol information do not compete
  with the user's decision.
- Expanded windows may expose more supporting fields, but compact and expanded
  layouts present the same object and actions rather than different products.

In the screen sections below, **Show** means that the data is available on that
surface, including through a clearly named supporting or Technical details
disclosure. **Directly shows** means it is visible in the initial state without
expansion. Conditional data and actions appear only in the states where they
apply. Bold field names describe semantic labels; they are not mandatory
word-for-word copy.

Names do not need redundant type prefixes:

- A client detail uses the friendly name as its title, for example **my
  laptop**, not **Client: my laptop**. Its reported hostname is separately
  labeled because it is a different value.
- A secret detail uses the secret name as its title, for example
  **aws-read-only**, with its type shown as supporting information rather than
  **Secret: aws-read-only**.
- A request or audit event without a user-assigned name uses a concise type or
  operation title.
- Inside another object's detail, a role label such as **Client** or **Secret**
  precedes a linked name when the relationship would otherwise be
  ambiguous.
- IDs, timestamps with distinct meanings, reported-versus-authoritative data,
  commands, paths, and security state are explicitly labeled. A familiar name
  already established by the screen title is not repeated as a labeled field.

User-assigned and reported names preserve their original capitalization even
when used as a screen title; the UI does not rewrite **my laptop** as **My
Laptop** merely because it occupies title position.

Capitalization in this specification identifies defined product concepts. UI
copy otherwise uses normal sentence case, reserving title case for screen
titles, headings, and labels where it is natural.

## Core screen model

### Primary navigation

The signed-in/account concept does not currently exist, and device setup is not
an everyday task. The primary destinations should therefore be:

1. **Requests** — the home destination and authoritative authorization inbox
   and history for secret use and mediated operations such as Git signing.
2. **Secrets** — typed capabilities that clients can request.
3. **Clients** — active, suspended, and previously paired CLI installations.

Settings remains a secondary destination rather than a primary domain.
Pairing is part of the client lifecycle, so pairing controls and pending
pairings belong in Clients. The navigation hierarchy stays the same across
phone and tablet layouts even when its visual presentation adapts.

### Navigation tree

```text
Initial setup
├── Welcome and setup
├── Claim pairing address
└── Notification setup display (embedded)

Main application
├── Requests
│   └── Request detail
│       ├── Secret use variant
│       ├── Git signature variant
│       └── Future authorized-operation variants
├── Secrets
│   ├── Incoming secret upload review
│   ├── Secret detail with embedded type-specific display
│   ├── New secret and type-selection editor
│   ├── Environment variable editor
│   ├── AWS configuration editor
│   └── SSH key action editors
├── Clients
│   ├── Pairing address and new-pairing controls
│   ├── Pending pairing review
│   └── Client detail
│       └── Reauthorization flow
└── Settings
    ├── Security & backup
    │   └── Factory reset Agentknock
    ├── Notifications
    ├── Subscription & billing
    ├── Audit log
    │   └── Audit event detail
    └── About
```

Each secret owns its default approval mode and optional per-client overrides.
General AI review instructions live at the top of Secrets; more specific instructions
live on the relevant client and secret.

### Application-wide exceptional state

**Surface:** Embedded display; no persistent status element is required in the
healthy state.

Every main destination shares the following application-level behavior:

- Normal connectivity is silent. When connection state materially affects the
  current screen, a small status icon may indicate synchronizing, offline, or
  attention required. It exposes a concise explanation and a relevant retry or
  repair action without shifting the surrounding layout.
- An actionable, non-modal banner appears when device keys are unavailable,
  notifications that the user expects are not deliverable, a client has a
  security warning,
  or synchronization has a durable error that requires user action. Temporary
  connection changes do not produce banners.
- A notification opens the exact request detail. If the request has already
  resolved, its final detail is still shown.
- Back navigation from a detail returns to the same list, filters, selection,
  and scroll position. Killing the app must not lose a pending operation or
  turn it into a modal on the next launch.
- Pending requests and destructive operations must never be inferred only from
  transient UI state. Screens render authoritative persisted state.
- Confidential material never appears in list rows, notifications, search
  indexes, widgets, recent-app previews, logs, diagnostics, or subscription
  screens.

Relay-internal administrative controls are not presented as product states.
When they affect observable behavior, the app uses its ordinary offline,
temporarily unavailable, retrying, or delivery-unknown presentation. It does
not add operator-suspension screens, banners, or client badges, and it does not
claim a cause that the public protocol cannot establish.

### Phone and tablet behavior

Requests, Secrets, Clients, and the Audit log are
list-detail flows:

- Compact windows show either list or detail, with normal back navigation.
- Expanded windows show list and selected detail side by side.
- Resizing preserves the selected item.
- Editors remain explicit navigation states rather than being conflated with
  transient confirmation dialogs.

Settings is an overview-to-sub-screen hierarchy, not a list-detail object
browser. Setup, focused action screens, and type-specific editors retain their
own navigation state on every window size.

## Initial setup and recovery

### S-01 — Welcome and setup

**Surface:** Initial destination screen shown only before **Device setup**.

**Purpose:** Explain the minimum product model and establish this app
installation as the device.

**Show:**

- A one-paragraph explanation: this device stores or issues the material used
  by typed secrets, which paired clients can request.
- Setup progress: device identity, notifications, first secret, and first
  client. Only device identity is required before entering the normal app.
- Whether the device has a secure screen lock and whether protected local key
  storage is available. Explain a blocking prerequisite only when the app truly
  cannot create its keys.
- Primary action: **Set up this device**.
- Secondary action: a short security and privacy explanation.

Request notification permission only after explaining why requests need it.

### S-02 — Claim pairing address

**Surface:** Setup editor screen opened from Welcome and setup.

**Purpose:** Create the device identity and claim its public pairing address.

**Show:**

- A generated three-English-word, dash-separated address.
- An editable address field with format and availability validation.
- **Another suggestion** and **Claim pairing address** actions.
- During an interrupted claim: the candidate pairing address, that the device
  identity is saved locally, the last failure, and **Retry**, **Choose
  another**, and **Discard** actions as applicable.
- A clear distinction between the public address and the private device keys.

On success, continue into the app and present contextual next actions: create a
secret, pair a client, and enable notifications. A separate “setup
complete” ceremony is unnecessary.

### S-03 — Enable notifications

**Surface:** Embedded setup display that launches the Android permission system
surface. It is not a mandatory standalone Agentknock screen.

**Purpose:** Ask for Android notification permission in context.

**Show:**

- That a push wake signal contains no request details. After the app retrieves
  and authenticates a request, it may show a notification with enough
  information to identify that request.
- That Agentknock marks request details as private and supplies generic public
  lock-screen content. When Android's user-controlled visibility settings show
  private content, it may include the client, request type, command, and secret
  names, but never Reasons, secret values, or private key material.
- That requests are still authoritative in the app if push delivery is delayed
  or disabled.
- **Enable notifications** to launch the system permission request and **Not
  now** to continue.
- If permission was denied, **Open system notification settings** instead of
  repeatedly showing the permission dialog.

The same state and action remain available under Notification settings.

### S-04 — Restored or transferred installation

**Surface:** Persistent recovery display and affected-object markers within the
normal application. It is not a blocking setup screen.

**Purpose:** Recover safely when backed-up metadata was restored but
device-bound private keys are unavailable.

**Show:**

- A persistent explanation that history and other metadata were restored, but
  private keys for the device identity and pairings, and stored secret
  material, cannot be decrypted on this device.
- Counts of affected secrets, clients, and outstanding requests.
- **Set up this device again** as the main recovery action.
- After replacement, a checklist to replace unavailable environment variables and SSH
  keys, reconfigure affected credential issuers, and pair clients again.
- A link to Security & backup explaining backup behavior.

This state must not hide the main application. Requests and metadata remain
readable; secrets with unavailable material are marked in place. Secret
use and pairing continuation fail closed until replacement is complete.
**Set up this device again** reuses the **Device setup** and **Claim pairing address**
screens in recovery mode rather than introducing another recovery editor.

## Requests

### R-01 — Request list

**Surface:** Primary destination and list screen.

**Purpose:** Be both the application home and the authoritative, durable inbox
and history for secret use and mediated operations such as Git signing.

Purely read-only or automatically handled operations do not appear here merely
because they arrived from a client. Secret-list operations, automatic
unpairing, local management actions, and non-actionable protocol failures are
recorded in the Audit log instead.

**Show:**

- One reverse-arrival-ordered feed, optionally grouped by day.
- Filters for **All** (default), **Needs action**, **In progress**, and
  **Completed**. A type filter or search can be added only when real volume
  justifies it.
- A pending/action-required count in the Requests navigation item.
- A refresh action and last synchronization failure when relevant. Normal live
  synchronization should not require manual refresh.

Each row directly shows:

- one request label, such as **Secret use** or **Git signature**;
- current status;
- the client's friendly name as supporting text, falling back to reported
  hostname and then an unknown-client label; and
- received time.

A secret use or signing row also shows the complete triggering shell command and a compact
secret-name summary when they fit, such as one name plus a count of additional
secrets. It never shows values. A completed row may show a compact
automatic-decision marker; the full decision source and delivery state belong to detail. The
request label makes redundant **Request:** and **Client:** prefixes
unnecessary.

Status language must distinguish **Needs approval**, **Needs review**, **Waiting
for client**, **Delivered**, **Approved**, **Rejected**, **Denied**,
**Cancelled**, **Expired**, **Unconfirmed**, and **Verification failed** as
appropriate to the request type. “Completed” alone is too vague for security
history.

Empty state explains that secret-use and signing requests will appear here.
Pairing requests belong under Clients, and secret uploads belong under Secrets.

The list must not automatically open a new request or place a modal over the
current screen. Several requests remain independently navigable.

All non-terminal requests are retained regardless of the history limit.
Terminal requests are kept in reverse arrival order up to a fixed product
limit; pruning removes the oldest terminal rows only. The exact limit remains
an open product decision. Request history participates in normal Android backup
and device transfer and is independent of audit-log retention.

### R-02 — Common request detail structure

**Surface:** Shared content within every request detail screen; not a separate
screen.

Every request detail starts with:

- an operation-specific title and a prominent, plain-language status;
- **Client** with the linked friendly name, plus separately labeled reported
  hostname when known;
- received time and any current expiry or response deadline;
- a short type-appropriate lifecycle timeline that distinguishes receipt,
  review or approval evaluation, the resulting action, and any confirmation still
  expected;
- decision source when applicable;
- any safe failure reason;
- the relay request ID and client ID under technical details; and
- **Delete from history** only for terminal requests.

The title does not repeat **Request:**. Primary content is the status, the
operation being authorized or reviewed, the client, and any available action.
Lifecycle and decision information is supporting content. **Request ID**,
**Client ID**, decided/completed/last-updated timestamps, versions, and
cryptographic diagnostics remain under Technical details.

Deletion removes the local history entry, not secrets, approval settings, or an active
client. Active or unsettled requests cannot be deleted. Existing audit-log
events about the request remain and show that the detailed request record is no
longer available.

Fields are grouped by trust:

1. **Agentknock state** — request IDs, cryptographic verification, lifecycle,
   decision, and delivery confirmation.
2. **Reported by client** — hostname, platform, command, executable, and
   working directory. These are authenticated as the paired client's report
   but not independently attested.
3. **Reason reported by client** — optional, untrusted text supplied with the
   request.

The warning about reported data should be concise and visible without making
the useful request data unreadable.

### R-03 — Pairing request detail

**Surface:** Pending pairing detail reached from Clients.

**Show:**

- Current pairing state: receiving cryptographic data, verification required,
  activating with relay, waiting for CLI finish, active, rejected, expired, or
  verification failed.
- Three formatted SAS choices and **None of the above** only while SAS
  verification is required.
- A clear warning that any wrong choice rejects the pairing.
- Reported hostname, platform, and received time.
- The proposed client name, initially derived from hostname when
  possible. It can be edited after pairing and need not complicate SAS
  selection.
- **Reject pairing** while the pairing still admits a user decision.
- After the correct SAS: explain whether the relay is activating the client or
  the CLI must run `agentknock pairing finish`.
- After activation: link to the new client detail.

Architecture, operating-system version, machine ID, CLI version, client ID,
and pairing address used remain available under Reported information or
Technical details; they do not compete with the SAS decision.

Current product behavior admits only one incomplete pairing. An existing
pending pairing remains in the list until the user accepts or rejects it.
Pausing new pairings does not cancel a pairing that the relay already admitted;
that request remains actionable.

The screen title is **Pairing**. The client name is a primary editable value,
not the screen title, because the pairing has not created a client yet. Reported hostname and other machine information retain labels that
make their reported origin clear.

### R-04 — Secret use request detail

**Surface:** Secret use variant of the request detail screen.

**Purpose:** Let the user make an informed decision about one atomic set of
requested capabilities without revealing confidential material.

**Show:**

- The exact requested secret names, descriptions, concrete types, and public
  delivery contracts.
- For an Environment variables secret, every environment variable name; for AWS temporary
  credentials, the account, role, lifetime, and fixed environment variables it
  provides; and for an SSH key, its algorithm, public-key fingerprint, and
  delivery helper.
- Missing secrets, unavailable material, invalid provider configuration, and
  conflicting provided environment variables as blocking errors.
- A reminder that approval authorizes the complete displayed set atomically;
  partial approval is unavailable.
- The reported command and each argument as separate structured values.
- Optional reason reported by the client, visually separated from the
  operation.
- Reported client identity: friendly name and hostname.
- Request timing and any expiry or cancellation state.
- The effective approval mode: automatic, AI review, ask every time, or deny.

A supporting **Execution details** section shows working directory, executable
path and mode, SHA-256 identity when reported, standard-stream kinds, and
launcher chain. Reported platform, architecture, OS version, machine ID, and
CLI version remain in the linked client or under Reported information. These
fields stay reachable but do not crowd the secret, command, and decision
actions.

Manual pending state provides **Deny** and **Approve**. Both affect only the
current request. Reusable behavior is configured on each secret rather than
created as a side effect of deciding a request.

Every approval revalidates the request and every secret's type,
configuration, contents, and availability before responding. If anything
changed since the screen was rendered, the decision stops and the updated
details must be reviewed again. The request records whether its decision came
from the user, approval settings, non-sensitive delivery, or AI review.

Terminal states must separately report:

- allowed and use or delivery confirmed;
- allowed but use or delivery unconfirmed;
- denied and client confirmation state;
- cancelled or expired before a response;
- invalid request or cryptographic verification failure; and
- an automatic decision, including the applicable approval mode and reviewer
  version where relevant.

The title is **Secret use**. **Requested secrets**, **Client**, **Command**,
**Working directory**, and **Reason reported by client** are labeled because
each has a distinct role in the decision. Arguments are rendered with the
command as one shell-readable value. The common request
status is not repeated in every group. Each requested secret is identified by
name without a **Secret:** prefix; its type and delivery contract are attached
supporting information. Complete environment variable or provided environment variable
names remain available within that secret's request detail without becoming
headline fields.

### R-05 — Git signature request detail

**Surface:** Git signature variant of request detail.

**Show:**

- the SSH key secret and requesting client;
- the parent invocation's shell-readable command;
- repository, branch, upstream, remote, and changed-path context when reported;
- the Git commit message when one can be identified safely;
- the exact content that will be signed, available without obscuring the
  decision-critical summary;
- the AI reviewer verdict and explanation when AI review left the decision to
  the user; and
- the elapsed time since the parent invocation in supporting details when it
  materially helps identify an unexpected late signing request.

Pending actions are **Deny** and **Sign**. Signing uses the private key on the
device and returns one SSHSIG signature; the private key is never sent to the
client. The screen distinguishes request approval from confirmed completion
and keeps protocol identifiers under Technical details.

### R-06 — Secret upload request detail

**Surface:** Incoming upload detail reached from Secrets.

**Purpose:** Let the user review a secret uploaded by a client before it
changes the device's secret collection.

**Show:**

- the uploading client and received time;
- the upload mode: **Create**, **Replace**, or **Update**, with a concise
  explanation of what that mode changes;
- uploaded secret name, description, and concrete type;
- for an Environment variables upload, every environment variable name with its value
  masked;
- a change summary that clearly separates added, changed, removed, and
  unchanged fields and environment variables; and
- the existing target secret for Replace or Update, with a link to its detail.

Create uploads a new secret and allows its name to be edited before approval.
Replace and Update target one existing secret by name; the target name and
immutable type cannot be changed during review. Replace uploads the complete
new contents, including removals. Update changes only the supplied
fields and environment variables.

Every newly added environment variable is marked **Sensitive** by default. When Replace or
Update supplies a new value for an environment variable that already exists under the same
name, that environment variable keeps its existing sensitivity setting. An upload never
silently changes an existing environment variable from non-sensitive to sensitive or vice
versa merely because its value changed. The user can change sensitivity later
from the approved secret's environment variable editor.

Actions are **Approve** and **Reject**. The approval review states the
resulting secret name and summarizes removals before confirmation.
Approved and rejected uploads remain in request history with the exact mode
and safe change summary, but never retain displayable values there.

An upload that targets a missing secret, conflicts with an existing name,
uses a different type from its Replace or Update target, or is otherwise
invalid cannot be approved. Such an upload is rejected without becoming an
actionable request and is recorded in the audit log with a safe reason.

The client completes upload after the device has durably received a valid
upload; it does not wait for the later user decision. The detail therefore
distinguishes **Received for review** from **Approved** and **Rejected**, and
does not imply that the original client will receive the eventual decision.

The title combines mode and name, such as **Create bootstrap** or **Update
aws-read-only**, rather than **Secret upload: aws-read-only**. The concrete
type is supporting information. Change categories and affected environment variable names
are labeled; individual unchanged fields need not occupy the primary summary.

## Audit log

The Audit log answers “what happened?” independently of whether an operation
required authorization. It is a first-level Settings destination, not another
primary navigation destination.

Requests and audit events are separate records. One request produces several
audit events as it moves through receipt, decision, response, and confirmed or
unconfirmed delivery. An operation that requires no decision produces audit
events without creating a request row.

### L-01 — Audit log list

**Surface:** List screen opened directly from Settings.

**Purpose:** Present a chronological record of security-relevant client,
device, user, approval-setting, and AI-review activity.

Record at least:

- Secret-list operations and their outcomes;
- request receipt, automatic, AI, or user decision, response, expiry, cancellation,
  and delivery confirmation;
- Secret upload receipt, approval, rejection, and validation failure;
- pairing activation, rejection, failure, and client-initiated unpairing;
- client rename, requested and confirmed suspension or resumption,
  reauthorization, and revocation;
- pairing-address claim and replacement, and pausing or resuming new pairings;
- Secret, environment variable, SSH key, and issuer-configuration creation,
  change, replacement, and deletion, plus approval-setting changes;
- device setup, recovery, and protected-key availability changes; and
- security-relevant authentication, cryptographic, and protocol failures.

Do not treat routine connection changes, screen navigation, rate or capacity
backoff, or diagnostic messages as audit events. The Audit log records product
and security actions, not internal events.

Each reverse-chronological row directly shows:

- a concise event-and-outcome title, such as **Client suspended** or **Secret
  upload rejected**;
- timestamp; and
- up to two supporting identities: actor and affected object, using their names
  without redundant type prefixes when context is already clear.

A compact indicator shows when a retained request detail is available. Exact
IDs, versions, event phases, and before-and-after data remain in event detail.

Filters cover event category, actor, outcome, affected object, and date range.
Search applies only to deliberately stored, non-secret display metadata.

### L-02 — Common audit event detail

**Surface:** Shared content within every Audit event detail screen; not a
separate screen.

**Show:**

- exact event type, timestamp, and outcome;
- actor and decision source;
- stable IDs and friendly-name snapshots for affected objects;
- safe before-and-after summaries for local management changes;
- related request, client, or secret links when those records still
  exist;
- delivery or client-confirmation state when applicable;
- a sanitized failure category; and
- app, CLI, and protocol versions when relevant.

The event-and-outcome phrase is the title; it is not prefixed with **Audit
event:**. Actor and affected-object relationships are labeled when both appear.
Friendly snapshots are primary supporting data, while stable IDs and versions
remain under Technical details.

An audit event retains enough names and safe metadata to remain intelligible
after the live object or bounded request record is deleted. It never contains
secret values, generated temporary credentials, private keys, device or client
tokens, encrypted blobs, raw messages, commands, Reasons, or other fields that are
not necessary to identify the event.

### L-03 — Secret-list events

**Surface:** Secret-list variant of the audit-event detail screen.

**Show:**

- the requesting client and its reported information;
- event phase: operation received, secret list sent, delivery confirmed,
  or failure;
- the number, names, and concrete types of secrets returned, captured on the
  operation-received event;
- the operation's stable correlation ID and links to its other audit events;
  and
- sent, delivered, unconfirmed, or verification-failed outcome as known at the
  time of this event.

The detail states that secret listing is automatic but audited because
names and usage patterns may be sensitive metadata. Descriptions, values,
provided environment variable names, and private-key material are not copied
into the Audit log.

### L-04 — Secret upload events

**Surface:** Secret-upload variant of the audit-event detail screen.

**Show:**

- the uploading client, upload mode, secret name, and concrete type;
- received, approved, rejected, or validation-failed outcome;
- a safe summary of which fields and environment variable names were added, changed, or
  removed when useful;
- the resulting secret link after approval;
- the related request link when a valid upload reached user review; and
- a safe rejection category for a missing target, name conflict, type mismatch,
  or invalid upload.

Values are never copied into the audit log. An upload validation failure may
have audit events without a request because it never became actionable.

### L-05 — Client unpairing events

**Surface:** Client-unpairing variant of the Audit event detail screen.

**Show:**

- the client's friendly name and reported information;
- event phase: authenticated operation received, pairing revoked, or client
  confirmation received;
- whether the device invalidated the pairing and outstanding responses at this
  phase;
- the operation's stable correlation ID and links to its other audit events;
  and
- a link to the historical Client detail.

Client-initiated unpairing normally resolves automatically and therefore does
not impersonate a user-facing authorization request. A client's force-remove
operation changes only that client's local state, so the device cannot show or
audit it unless the client later communicates with the device again.

### L-06 — Unsupported or unverifiable operation event

**Surface:** Failure variant of the Audit event detail screen.

**Show:**

- source client if authentication reached that point;
- operation type and received time;
- safe error category: unsupported operation, invalid message, unsupported
  version, or verification failure;
- whether a denial or error response was delivered; and
- app and CLI versions under technical details.

Do not render arbitrary unknown message content or retain it merely for
debugging. It may contain sensitive or maliciously crafted data.

### L-07 — Retention and integrity behavior

**Surface:** Behavior definition; no separate screen.

Request history and the Audit log are retained independently. Every successful
security-relevant change produces its audit event, while retries or repeated
delivery of the same action do not create duplicates.

Audit events are append-only: recorded events are never altered, and individual
events cannot be deleted. This is an operational audit trail, not a
tamper-evident security boundary against someone who controls the device.

The initial product retains audit events for one year. Retention removes whole
expired rows during normal maintenance and is the only deletion path other
than Factory reset. Shorter periods or **Keep forever** can be added later if
real use requires a setting. The Audit log participates in normal Android
backup and device transfer independently of whether protected secret material
remains decryptable.

## Secrets

Secrets use one flat, global namespace. A name therefore identifies a secret
without a separate type qualifier: `aws-read-only` cannot simultaneously name
an Environment variables secret and an SSH key secret. The type is immutable
after creation. Secrets are not nested and do not share child objects;
composition happens in a request, such as:

```text
agentknock exec --secret aws-read-only --secret production-ssh -- command
```

`-s` is the shorthand for each `--secret` occurrence.

### S-01 — Secret list

**Surface:** Primary destination and list screen.

**Purpose:** Browse and manage the typed capabilities exposed to clients.

Each row directly shows:

- Secret name as the primary text, without a **Secret:** prefix;
- concrete type;
- availability or attention state; and
- one compact type-specific summary, such as an environment variable count, AWS account and
  role, or abbreviated SSH fingerprint.

Description, complete provided environment variable names, delivery mechanism,
approval settings, created and updated times, and any high-value classification
belong to secret detail.

Primary action: **New secret**.

Empty state explains that each secret supplies one kind of capability and
that a command can request several secrets together. Examples should include
an Environment variables secret, AWS temporary credentials, and an SSH key.

### S-02 — New secret and type selection

**Surface:** Secret creation editor screen.

**Show:**

- globally unique secret name;
- optional description; and
- one concrete type: **Environment variables**, **AWS temporary credentials**,
  or **SSH key**.

The type choice determines the configuration form, the environment variables
it provides when applicable, and delivery behavior. There is no generic
“dynamic” type with a separate provider selector: provider-specific semantics
belong to types such as AWS temporary credentials. A saved secret cannot
change type; the user creates a new secret instead.

Validate empty names, leading or trailing whitespace, and global uniqueness
before opening or saving the type-specific editor.

The screen title is **New secret**. Because no secret name has yet been
established as a title, **Name**, **Description**, and **Type** are explicit
field labels. Selecting a type continues within the creation flow; it does not
create a new Settings or primary-navigation destination.

### S-03 — Secret detail

**Surface:** Detail screen for one secret.

Every secret detail shows:

- name, description, immutable concrete type, created time, updated time, last
  requested time, and aggregate request count;
- availability and any type-specific configuration error;
- its public delivery contract and, where applicable, the fixed environment
  variables it provides;
- its default approval mode and any per-client overrides;
- secret-specific AI review instructions; and
- recent authorization requests and audit events filtered to this secret,
  linking to their normal details.

The detail then presents the type-specific information defined below. Stored
values and private keys remain masked. Generated temporary credentials are not
exposed or retained merely because a request completed.

Secret actions are **Edit**, **Rename**, and **Delete**, plus type-specific
actions such as replacing stored material or testing issuer configuration.
Renaming changes the CLI-facing handle but preserves the stable internal ID,
approval settings, and history. Deleting makes future requests by that name fail;
history retains the secret name, type, and request metadata, never values.

Saving, renaming, and deleting are security-sensitive management actions and
use the device's configured authentication controls.

The secret name is the screen title. Type, availability, and description are
primary or supporting information directly below that identity without
repeating **Secret name**. Created, updated, and usage timestamps are labeled
because their meanings differ. A stable secret ID, if ever exposed, belongs
under Technical details.

### S-04 — Environment variables secret

**Surface:** Type-specific content within secret detail, plus a separate
environment variable editor screen for **Add environment variable** and **Edit
environment variable**.

**Purpose:** Manage a cohesive set of fixed environment variables that must be
released together.

The secret detail directly shows:

- one row per environment variable with its name as primary text;
- sensitivity and availability for each environment variable; and
- type-level last-used state when useful.

Created, updated, and value-updated times and notes are available after opening
that environment variable rather than filling every secret-detail row. The
environment variable editor shows labeled **Name**, **Value**, **Sensitive**,
and **Notes** fields, plus the existing environment variable's lifecycle
timestamps as read-only supporting information.
The value remains masked until the user deliberately reveals or replaces it.

An existing environment variable editor uses the environment variable name as
its title; the add flow uses **New environment variable**. Neither needs an
**Environment variable:** title prefix.

Each environment variable owns its fixed name and value. The app must
never reinterpret an existing value under a different name or sensitivity
setting. If the value is unavailable, changing either property requires
replacing the value. Non-sensitive environment variables are permitted because not every
value, such as `AWS_REGION`, is confidential; all values remain encrypted at
rest regardless. Changing an environment variable's value does not change its sensitivity.

Actions are **Add environment variable**, **Edit environment variable**, and
**Delete environment variable**.
Editing can reveal or copy a value only after device authentication when its
sensitivity requires it. The editor never substitutes an empty value and does
not silently merge duplicate names.

### S-05 — AWS temporary credentials secret

**Surface:** Type-specific content within secret detail, plus an editor screen
for its AWS configuration.

**Purpose:** Configure one AWS temporary-credential provider whose generated
environment variables share one lifetime and must remain synchronized.

The secret detail directly shows:

- AWS account and role;
- the fixed list of environment variables provided to the client, including
  `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN`, plus
  optional names such as `AWS_REGION` when the type supports them;
- configured lifetime;
- availability of any long-lived authorization material, without revealing
  it; and
- last generation time, outcome, and safe failure category.

The editor shows the labeled authorization, scope, account, role, and lifetime
inputs required by this concrete type. Provider-specific technical identifiers
that are not needed to recognize the account or role remain secondary.

AWS is inherent in this secret type; there is no separate provider flag. One
request produces the full credential set atomically with one expiry. The same
fixed list of provided environment variable names is visible on the device and
in the CLI's secret list. Generated values are retained only as long as
delivery and audit correctness require and do not become durable stored
environment variables.

### S-06 — SSH key secret

**Surface:** Type-specific content within secret detail, plus focused Generate
key, Import key, and Replace key editor screens.

**Purpose:** Manage one SSH key and expose it through an SSH-specific helper.

The secret detail directly shows:

- key algorithm, public key, fingerprint, and comment;
- whether private-key material is available;
- whether the key was generated on the device or imported;
- the configured helper/delivery behavior once that behavior is defined; and
- created, updated, material-updated, and last-used times.

Fingerprint and availability are the primary key summary. Algorithm, comment,
and public key are labeled supporting fields. Lifecycle timestamps and delivery
details remain secondary; private-key material is never a display field.

Generate key shows the selectable supported algorithm, optional public comment,
and the fact that a new private key will be created on this device. Import key
shows the detected algorithm, public-key fingerprint, and validation result
before saving. Replace key shows both the current and proposed public
fingerprints and clearly states that future access uses the replacement. These
editor screens never render private-key bytes after input.

Actions are **Generate key** or **Import key**, **Replace key**, and
type-appropriate public-key copy actions. Private-key material remains hidden
unless a later explicit export feature is designed. The secret has no
environment variables and cannot contain multiple keys.

Stored confidential secret material remains encrypted at rest. If it is
unavailable after restore or transfer, metadata and history remain visible,
but the material must be replaced before the secret can be used or its bound
properties changed.

## Clients

### C-01 — Client list

**Surface:** Primary destination and list screen.

**Purpose:** Manage the installations that can ask this device for secret
metadata or values.

Each row directly shows:

- user-assigned friendly name as primary text, falling back to reported
  hostname and then an unknown-client label;
- state: active, suspending, suspended, resuming,
  stale/reauthorization required, security attention, revoking, revoked, or
  keys unavailable on this restored device;
- reported hostname or compact platform summary when it adds information beyond
  the friendly name; and
- last activity time.

A pending-request indicator appears when nonzero because it requires attention.
Paired time, full platform information, and exact request counts belong to
client detail. Rows use **my laptop**, not **Client: my
laptop**.

Default view prioritizes active and attention-requiring clients. A
**Previous** filter exposes revoked clients without mixing them into the
normal active list.

The persistent pairing controls show whether new pairings are accepted. The
empty state adds a copyable `agentknock pairing start <PAIRING_ADDRESS>` command
when pairing is enabled, or points to the adjacent resume control when paused.
Pending pairing attempts open their pairing detail rather than becoming
half-created client rows.

### C-02 — Client detail

**Surface:** Detail screen for one client.

**Show:**

- friendly name, editable by the user;
- authoritative Agentknock state and any security warning;
- reported hostname, platform, architecture, OS version, machine ID, CLI
  version, and client ID;
- paired, last authorization request, last successful request, last secret-list
  operation, and last seen times when available;
- client-specific AI review instructions;
- continuity-key rotation status in user language: healthy, stale,
  reauthorization required, or competing copy detected;
- authorization requests and audit events filtered to this client; and
- an explanation that reported machine information is not independent host
  attestation.

Actions:

- **Rename**;
- **Suspend** or **Resume**;
- **Reauthorize** when stale or after a copied-state warning;
- **Revoke client**.

The client remains **Suspending**, **Resuming**, or **Revoking** until the relay
confirms the requested state. These transitions survive leaving the screen or
restarting the app and remain visibly retryable after a delivery failure.

Once suspension is confirmed, the client cannot send new traffic and its
pairing is retained for later resumption. A request already delivered to the
device remains visible and can still be decided, but its response cannot reach
the client until the client resumes and reconnects; it may expire first.
Suspension does not revive messages already discarded or requests already
expired.

Revocation is authoritative and permanent for that pairing: future requests
fail, outstanding exchanges and delayed responses are terminated, the CLI must
pair as a new client, and local history remains.

A competing-copy warning prevents automatic approval for that client until the
user explicitly reauthorizes or revokes it.

The friendly name is the screen title. State and any security warning are
primary. **Reported hostname**, **Platform**, **Architecture**, **OS version**,
and **Machine ID** remain explicitly labeled because they are client-reported
attributes, while **Client ID** and CLI/protocol versions belong under
Technical details. Related Requests and Audit events are named sections rather
than fields competing with the client identity.

### C-03 — Client reauthorization

**Surface:** Focused action screen opened from client detail only when the
client is eligible for reauthorization.

**Purpose:** Let the user deliberately restore trust after stale continuity
state or a competing-copy warning.

**Show:**

- the friendly client name and current warning;
- the reported hostname and platform snapshot being reauthorized;
- why reauthorization is required and what evidence the user must compare or
  confirm once that protocol is defined;
- progress and any expiry while reauthorization is incomplete;
- the effect on automatic approval; and
- **Cancel**, **Reauthorize**, and **Revoke client** only in the states where
  each action is valid.

The screen title is **Reauthorize my laptop**, substituting the friendly name,
not **Reauthorize client: my laptop**. Technical identifiers and protocol
transcript state remain secondary. Until a concrete verification contract
exists, the UI must not substitute a generic confirmation that treats reported
machine metadata as proof.

## Approval settings and AI review

Approval is configured where its subject is understood:

- each secret has a default mode: **Approve automatically**, **Ask AI**,
  **Ask every time**, or **Always deny**;
- a secret may override that mode for an individual client;
- general AI review instructions live at the top of Secrets;
- client and secret instructions live on their respective detail screens; and
- a pending request can be approved or denied once without changing reusable
  settings.

For a request containing multiple secrets, each secret is evaluated under its
effective mode and the least permissive result governs the atomic request.
Non-sensitive material, such as an SSH public key, is delivered without an
approval decision. A later protected operation using the same secret, such as
Git signing, is evaluated separately.

### A-01 — General AI review instructions

**Surface:** Compact editor opened from the top of Secrets; not a separate
navigation destination.

The row shows only whether general instructions are set. Its editor explains
that the instructions apply to every AI review and combine with instructions
on the relevant client and secret. AI availability and billing belong in
Subscription & billing; approval modes and client overrides remain on secret
detail screens.

## Settings and secondary screens

### T-01 — Settings overview

**Surface:** Settings destination screen.

**Purpose:** Present infrequently changed application behavior and status.

Rows show their current state in supporting text and open focused subscreens:

- **Security & backup** — device authentication, key protection, Android backup,
  recovery readiness, stored-data summary, and Factory reset.
- **Notifications** — Android permission and the separate action-required and
  silent background-processing categories.
- **Subscription & billing** — free core access and AI-review entitlement.
- **Audit log** — security and product activity.
- **About** — product explanation, public links, version, source revision,
  device ID, and relay address.

Use conventional Android preference rows and section headings. Status appears
as supporting text only when it helps the next decision. Settings must not
duplicate controls that have a clearer domain home elsewhere.

### T-02 — Pairing controls

**Surface:** Persistent compact display at the top of Clients; not a Settings
screen.

**Show:**

- pairing address with copy action;
- new-pairing state: **Accepting new pairings** or **New pairings paused**;
- **Pause new pairings** or **Resume new pairings**;
- **Change pairing address**; and
- the exact pairing command only when there are no clients and no pending
  pairing.

Pausing affects only previously unknown pairing attempts. It keeps the pairing
address, does not cancel an already admitted pairing request, and remains in
effect until the user explicitly resumes it; there is no automatic timeout.

Changing the pairing address affects only future pairings. Existing clients,
secrets, approval settings, and history are unaffected. The current address remains in
use until the replacement has been claimed successfully, and an interrupted
change remains resumable.

Never display or export the device token or private device keys. Device ID and
other installation details belong in About, while cryptographic protection
belongs in Security & backup.

### T-03 — Notifications

**Surface:** Settings sub-screen that links to Android notification system
surfaces where Android owns the choice.

**Show:**

- overall Android notification access and a direct route to system settings;
- **Requests needing approval**, a prominent category allowed to alert the user;
- **Background processing**, a quiet category for brief work triggered by push;
  and
- a concise delivery warning only when the app knows notification delivery is
  impaired.

Do not duplicate Android's channel controls. Link to system settings for sound,
vibration, lock-screen visibility, and complete enable/disable behavior.

The effective notification state and the action needed to change or repair it
are primary. Push registration internals and wake timestamps are not user
settings and stay out of this screen.

An actionable notification appears only after the app has retrieved and
authenticated the request. A generic push wake can never authorize anything.
Agentknock supplies generic public lock-screen content and marks request details
as private. Subject to Android's user-controlled visibility settings, private
content shows enough context to distinguish the client, request type, command,
and secrets without showing confidential material or a reason reported by the
client.

Pending secret use notifications provide **Deny** and **Approve**:

- **Deny** applies only if the same request is still pending and is safe to
  repeat without changing the outcome.
- **Approve** performs the same complete revalidation as approval in the app
  and follows the configured device-authentication policy. When Android cannot
  provide a required authenticated interaction from the notification, the
  action opens the exact request detail to finish approval.

Dismissing a notification has no effect on its request, and a notification is
removed or updated when the request resolves elsewhere.

A secret upload notification identifies its client, mode, and secret name and
opens the upload detail for review. It does not offer an approval shortcut
before the user has seen the uploaded changes.

### T-04 — Security & backup

**Surface:** Settings sub-screen.

**Show:**

- device-authentication policy first, using one Android-owned authentication
  prompt only when the configured policy requires it;
- secure screen-lock availability;
- local encryption-key state and whether hardware-backed protection is in use
  when Android can report it reliably;
- what Android backup includes: all metadata and encrypted stored values;
- what Android backup excludes: device-bound encryption keys, meaning restored
  encrypted values and pairings cannot be used without a recovery method;
- the current key-recovery status without advertising unimplemented methods;
- compact counts for secrets, clients, and audit events; and
- a visually separated route to Factory reset Agentknock.

This screen reports and explains security posture and backup consequences; it
does not manufacture low-value security toggles. Android-owned screen lock and
biometric enrollment open the relevant system settings.

Authentication policy and backup/recovery consequences are primary.
Cryptographic implementation detail and counts are supporting information at
the end rather than separate status cards for every mechanism.

### T-07 — Factory reset Agentknock

**Surface:** Focused action screen reached manually from Security & backup.

**Purpose:** Give the user a deliberate way to erase Agentknock completely and
recover by starting again when the current device can no longer be used.

This is a dedicated full-screen destructive flow, not a routine confirmation
dialog. It is manually reachable from Security & backup even while the relay is
unavailable.

**Show before reset:**

- an unmistakable **This cannot be undone** warning;
- every local category that will be erased: device identity and keys, secrets
  and values, Clients, Requests, approval settings, Audit log, and settings;
- the remote state Agentknock will attempt to delete: the relay device, client
  registrations, pending exchanges, and push registration;
- that deletion removes live relay state while any limited infrastructure
  recovery retention follows the published privacy policy;
- that every client must pair again;
- that setup starts from the beginning with a new device identity and pairing
  address;
- that the previous pairing address may remain unavailable during its relay
  quarantine; and
- whether remote deletion can currently be attempted and whether it was
  confirmed.

The final action is **Factory reset Agentknock**. It requires device
authentication and a deliberate confirmation stronger than accepting a generic
dialog, such as entering a displayed confirmation phrase. The destructive
button remains visually and spatially separate from retry, cancel, and normal
data-management actions.

Agentknock first asks the relay to delete the device. If deletion is confirmed,
it erases all local state and returns to initial setup. If the relay
unambiguously confirms that the device is already absent, the same local reset
may continue after the full confirmation.

If remote deletion cannot be confirmed because the relay is unreachable or
rejects the operation, nothing is erased automatically. The screen offers
**Try again** and **Cancel**. A separately confirmed **Reset this app anyway**
remains available for recovery, with an explicit warning that inaccessible
remote state may remain until the relay's inactivity cleanup removes it.

Factory reset is never suggested, preselected, or opened in response to a
connection, synchronization, authentication, or protocol error. In particular,
one or many `404` responses, regardless of duration, are not proof that the
relay device registration was permanently deleted. Such failures retain all
local state and use ordinary retry behavior. If the registration truly is gone
or permanently inaccessible, Factory reset remains a manual recovery path
rather than an automatic diagnosis.

Using Android's system **Clear storage** action erases only local app data and
cannot request relay deletion. Help and this screen explain that distinction.

### T-09 — Subscription & billing

**Surface:** Settings sub-screen.

**Purpose:** Explain the free core product and manage the optional AI-review
subscription without making billing a primary application destination.

For a free user, show:

- that secret storage and release and manual approvals are included for
  everyone;
- that the subscription adds AI review;
- localized price and billing period returned by Google Play;
- trial or introductory-offer terms when eligible; and
- **Subscribe with Google Play**.

For a subscriber, show:

- current plan and entitled capabilities;
- renewal or entitlement-end date;
- recurring localized price when available;
- state: active, canceled but active until date, grace period, on hold, paused,
  pending purchase, expired, or verification unavailable;
- the action appropriate to that state: **Manage subscription**, **Fix payment
  method**, **Reactivate**, **Subscribe again**, or **Refresh purchase status**;
  and
- a direct link to the app's subscription in Google Play.

The purchase itself uses the Google Play purchase sheet. Agentknock grants paid
capabilities only after backend verification of a purchased state and records
entitlement separately from cryptographic request authorization. A pending
purchase grants nothing. Grace-period access remains active; account hold and
expiry remove paid entitlement.

The screen must work sensibly when Play Billing is unavailable, including a
sideloaded open-source build. It explains that billing is unavailable in that
installation rather than spinning forever or inventing a price.

The entitlement state, applicable price, and available action are primary.
Billing lifecycle terminology and dates are labeled supporting information.
The screen title is **Subscription & billing**. Do not imply that core features
are a lower plan or that the entire product requires payment.

Free, subscribed, payment-attention, expired, and billing-unavailable are states
of this one screen, not separate billing screens.

### T-10 — Contextual paid-feature explanation

**Surface:** Contextual explanation surface opened from a paid capability; not
a Settings or primary-navigation destination. Its presentation may adapt to the
available window without changing its content or actions.

**Purpose:** Explain why a selected capability is unavailable without turning
ordinary app navigation into repeated paywall interruptions.

**Show:**

- the exact feature the user tried to use;
- what the feature does and any trust-boundary change;
- current entitlement state;
- localized offer information from Google Play; and
- **View plan** plus a clear back/dismiss path.

Never cover a live manual approval with a paywall. Billing failure must not be
misrepresented as a secret use denial or cryptographic failure.

### T-12 — About

**Surface:** Settings sub-screen.

**Show:**

- a concise explanation that Agentknock keeps developer secrets on the phone
  and provides them only to approved commands;
- website, privacy-information, and source-code links;
- app version and exact source revision;
- developer name;
- Device ID and relay address as labeled installation information.

Keep this compact enough to fit a normal phone screen. It is product identity
and supporting information, not a second diagnostics or security screen.

## Confirmations and authentication surfaces

**Surface:** Transient in-app confirmations, small single-field editors, and
Android-owned authentication surfaces; none are navigation destinations.

These are dialogs or system authentication surfaces, not independent
navigation destinations:

- allow or deny a secret use request when a confirmation is useful;
- accept or reject a secret upload;
- delete an environment variable or secret;
- suspend, resume, reauthorize, or revoke a client;
- pause or resume new pairings;
- change pairing address or discard an incomplete claim;
- reveal/copy a sensitive value.

Confirmation copy names the affected object and consequence. Destructive
buttons use the precise verb—**Revoke**, **Delete**, **Clear**, **Deny**—rather
than a generic **OK**.

Authentication is requested only after the user starts the operation.
Cancellation leaves persisted state unchanged.

Factory reset is deliberately excluded from this list because it uses the
dedicated full-screen flow defined above.

Simple Rename actions use a small labeled name editor rather than gaining a
screen in the navigation tree. Changing the pairing address reuses the Claim
pairing address editor in replacement mode, adding the effect on future
pairings and replacement progress. Google Play owns the purchase system
surface launched from Subscription & billing.

## Cross-screen behavior and presentation

### Timestamps and outcomes

- Use the unambiguous `yyyy-MM-dd HH:mm:ss` format in the device time zone when
  an exact timestamp is shown. Lists may use shorter relative time where the
  surrounding date remains unmistakable.
- Never derive “delivered” from the device's approval alone. Show it only after
  authenticated client confirmation.
- Unknown or ambiguous delivery remains **Unconfirmed**, not successful.

### Identifiers

- Friendly client names are primary.
- Hostname, machine ID, client ID, device ID, and request ID remain available
  under reported or technical details.
- IDs are selectable/copyable where useful but are never described as secrets
  or proof of physical-machine identity.

### Confidential material and sensitive metadata

- Values containing secret material are masked by default and omitted entirely
  from request presentation and history. Concrete non-sensitive values are
  revealed only in their management UI when useful.
- Secret names, environment variable names, commands, paths, reasons, hostnames, and
  usage patterns are sensitive metadata even though they are not secret values.
- Push wake signals and public lock-screen notification content remain generic.
  Agentknock marks request details as private; Android's user-controlled
  visibility settings determine whether the minimum metadata needed for an
  informed one-time action appears on the lock screen.
- Screens that show command or reason data should opt out of unintended
  system capture where practical, without claiming this defeats a compromised
  device.
- Search, analytics, crash reports, and diagnostics do not ingest request
  content.

### Loading, empty, and failure states

Every data screen defines:

- initial loading;
- genuinely empty data;
- locally available but currently offline data;
- stale data while synchronization retries;
- item removed while its detail is open;
- restored metadata with unavailable encrypted values;
- unsupported data from a newer peer; and
- a retryable versus permanent failure.

Offline history and metadata remain browsable. Actions that require the relay
say they are queued only when they will survive the app exiting; otherwise they
fail clearly and leave the original state intact.

## Open product decisions

These decisions materially affect the user contract or screen structure and
should be resolved before the corresponding functionality is specified in
detail:

1. **Request history limit.** Non-terminal requests are never pruned, while
   terminal Requests are bounded independently of the one-year Audit log.
   Decide the exact terminal-record limit; 100 is the current candidate.
2. **Client friendly name timing.** Decide whether the default reported
   hostname is accepted automatically after correct SAS or whether naming is a
   required pairing step. It remains editable either way.
3. **Client reauthorization contract.** Define the verification evidence,
   protocol lifecycle, expiry, and effect on automatic approval before the
   focused reauthorization screen is finalized. Reported machine metadata is
   not sufficient evidence by itself.
4. **Secret type contracts.** Define the exact public metadata, fixed provided
   environment variable names where applicable, request result, delivery
   behavior, and composition constraints for each type before its screens are
   finalized.
5. **AWS credential generation.** Choose the authorization mechanism,
   configuration fields, storage of any long-lived material, duration limits,
   error model, and revocation behavior for AWS temporary credentials.
6. **SSH delivery.** Define the client-side helper, authorization boundary,
   operation lifecycle, and whether the first version supports signing only or
   any other key operation.
7. **Sensitivity granularity.** An environment variable can have a sensitivity choice,
   while inherently confidential secret types require protected handling.
   Decide whether compound type metadata ever needs field-level sensitivity.
8. **High-value scope.** Decide whether step-up authentication is attached to
   an entire secret, individual environment variables within an Environment variables
   secret, or both. Secret-level classification works consistently across
   all secret types.
9. **Paid capabilities.** No feature-to-plan mapping or price is assumed here.
   Decide what remains free, what is paid, usage limits, and whether manual
   device-held release always remains available during billing problems.
10. **Subscription identity.** Decide how a Google Play purchase is bound to the
   pseudonymous Agentknock device identity, and what restoration or
   multiple-device behavior is promised before the backend entitlement model
   is designed.
11. **AI review and the Ask AI label.** **Ask AI** is the provisional approval-mode
   label. Revisit it before release while defining the provider,
   visible fields, evaluation gates, fallback behavior, and subscription
   economics.

## External references

### Dynamic credential terminology

- [HashiCorp Vault secrets engines](https://developer.hashicorp.com/vault/docs/secrets)
- [HashiCorp Vault glossary](https://developer.hashicorp.com/vault/docs/glossary)
- [AWS temporary security credentials](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_temp.html)

### Android guidance

- [Build adaptive navigation](https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation)
- [Canonical adaptive layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts)
- [Android settings design guidance](https://developer.android.com/design/ui/mobile/guides/patterns/settings)
- [Android notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Google Play Billing subscriptions](https://developer.android.com/google/play/billing/subscriptions)
- [Google Play subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions)
- [Google Play Billing security](https://developer.android.com/google/play/billing/security)
