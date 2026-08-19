# Agentknock Android application screen specification

Status: review draft

Last updated: 2026-08-16

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

The proposal is grounded in:

- the Agentknock PRD;
- the current Android application and its persisted data;
- the current CLI, including pairing, Profile listing and upload, execution,
  and unpairing;
- the relay's current connection, enrollment, and push-registration behavior;
  and
- current Android adaptive-layout, settings, notification, and Google Play
  Billing guidance.

Where these sources disagree, the disagreement is called out in **Open product
decisions** instead of being silently resolved.

## Product vocabulary

Use these terms consistently in user-facing copy:

| Term | Meaning |
| --- | --- |
| **Device** | This Android app installation. It owns the device identity, device token, and phone-held cryptographic keys. |
| **Pairing address** | The human-readable three-word address used only to begin a new pairing. It routes pairing attempts to this device; it is not authentication. |
| **Client** | One paired Agentknock CLI installation, whether it runs on a laptop, server, VM, container, or hosted execution environment. |
| **Profile** | A globally named, atomically requested capability with exactly one concrete type, such as Environment variables, AWS temporary credentials, or SSH key. |
| **Profile type** | The immutable schema and delivery contract of a profile. Provider-specific behavior is expressed by a concrete type, such as AWS temporary credentials, rather than a separate provider field on a generic dynamic type. |
| **Variable** | One fixed name and value inside an Environment variables profile. A variable has its own sensitivity and lifecycle metadata. |
| **Delivery** | How a profile is made usable by a client, as defined by its type: environment variables, an SSH helper, or a later type-specific adapter. |
| **Request** | A durable authorization workflow that requires a user decision or evaluation by a phone-held policy. It remains in the request history after completion, subject to bounded retention. |
| **Profile proposal** | A Create, Replace, or Update change uploaded by a Client for review on the Device. It does not create or alter an active Profile until the user accepts it. |
| **Audit event** | One append-only record of a security-relevant action or state transition, including operations that require no authorization. Audit events form the Audit log. |
| **Policy** | A phone-held rule that may decide matching profile access requests automatically. Every match still creates a unique, single-use, auditable request. |

Use *client* for the paired CLI installation throughout ordinary UI as well as
in technical details such as “Client ID.” Reserve *device* for this Android app
installation and *environment* for an actual execution environment or an
environment variable.

A profile is the reusable unit that a client requests. Profile names occupy one
flat, globally unique namespace; type is not part of a profile's identity. A
command composes capabilities by requesting multiple profiles, for example
`agentknock exec -p aws-read-only -p production-ssh -- command`. Profiles do
not contain or reference other profiles.

Each profile has one concrete, immutable type. An Environment variables
profile contains a cohesive set of fixed Variables. An AWS temporary
credentials profile owns its generation configuration and provides a fixed,
publicly listed set of environment variables together with one lifetime. The
names are visible before access is requested; their generated values are not
stored Variables. An SSH key profile owns one key and uses SSH-specific delivery
rather than pretending the key is an environment variable. Changing a profile
to another type means creating a new profile.

Use *secret* only as a general description of confidential material, not as a
separate product object. Sensitivity is an independent property of Variables;
a useful environment variable may be non-sensitive even though all stored
values are encrypted. Types with inherently confidential material enforce
appropriate handling. Use *credential* for authentication material such as an
AWS credential set, not as the structural name for every profile.

## Screen and field conventions

Each section identifies its **Surface** so that a named product concept is not
mistaken for a separate screen:

- A **destination screen** is directly reachable from primary navigation or
  Settings and owns a list or overview.
- A **detail screen** is opened for one durable object. Request and Audit event
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

- A Client detail uses the friendly name as its title, for example **my
  laptop**, not **Client: my laptop**. Its reported hostname is separately
  labeled because it is a different value.
- A Profile detail uses the Profile name as its title, for example
  **aws-read-only**, with its type shown as supporting information rather than
  **Profile: aws-read-only**.
- A policy detail uses the policy name as its title. A Request or Audit event
  without a user-assigned name uses a concise type or operation title.
- Inside another object's detail, a role label such as **Client**, **Profile**,
  or **Policy** precedes a linked name when the relationship would otherwise be
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
   and history.
2. **Profiles** — typed capabilities that clients can request.
3. **Clients** — active, suspended, and previously paired CLI installations.

Settings remains a secondary destination rather than a fourth primary domain.
Device and pairing management belongs there and is linked contextually from
pairing empty states. The navigation hierarchy stays the same across phone and
tablet layouts even when its visual presentation adapts.

### Navigation tree

```text
Initial setup
├── Welcome and setup
├── Claim pairing address
└── Notification setup display (embedded)

Main application
├── Requests
│   └── Request detail
│       ├── Pairing variant
│       ├── Profile access variant
│       ├── Profile proposal variant
│       └── Future authorized-operation variants
├── Profiles
│   ├── Profile detail with embedded type-specific display
│   ├── New profile and type-selection editor
│   ├── Variable editor
│   ├── AWS configuration editor
│   └── SSH key action editors
├── Clients
│   └── Client detail
│       └── Reauthorization flow
├── Approval policies (from Automation or context)
│   ├── Policy detail
│   └── Create or edit policy
└── Settings
    ├── Device & pairing
    ├── Notifications
    ├── Security
    ├── Automation
    ├── Data and history
    │   ├── Audit log
    │   │   └── Audit event detail
    │   └── Factory reset Agentknock
    ├── Connection diagnostics
    ├── Plan and billing
    ├── Help
    └── About, privacy, and licenses
```

Approval policies are reachable both from **Settings > Automation** and from
the profile, client, and request they affect. They are a domain workflow,
not merely a collection of switches.

### Application-wide exceptional state

**Surface:** Embedded display; no persistent status element is required in the
healthy state.

Every main destination shares the following application-level behavior:

- Normal connectivity is silent. When connection state materially affects the
  current screen, a small status icon may indicate synchronizing, offline, or
  attention required and link to Connection diagnostics. The icon has an
  accessible text description but need not consume a labeled row or banner.
- An actionable, non-modal banner appears when device keys are unavailable,
  notifications that the user expects are not deliverable, a Client has a
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
not add operator-suspension screens, banners, or Client badges, and it does not
claim a cause that the public protocol cannot establish.

### Phone and tablet behavior

Requests, Profiles, Clients, the Audit log, and policy management are
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

**Surface:** Initial destination screen shown only before Device setup.

**Purpose:** Explain the minimum product model and establish this app
installation as the phone-held device.

**Show:**

- A one-paragraph explanation: this device stores or issues the material used
  by typed profiles, which paired clients can request.
- Setup progress: device identity, notifications, first profile, and first
  client. Only device identity is required before entering the normal app.
- Whether the device has a secure screen lock and whether protected local key
  storage is available. Explain a blocking prerequisite only when the app truly
  cannot create its keys.
- Primary action: **Set up this device**.
- Secondary action: a short security and privacy explanation.

Request notification permission only after explaining why Requests need it.

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
profile, pair a client, and enable notifications. A separate “setup
complete” ceremony is unnecessary.

### S-03 — Enable notifications

**Surface:** Embedded setup display that launches the Android permission system
surface. It is not a mandatory standalone Agentknock screen.

**Purpose:** Ask for Android notification permission in context.

**Show:**

- That a push wake signal contains no Request details. After the app retrieves
  and authenticates a Request, it may show a notification with enough
  information to identify that Request.
- That Agentknock marks Request details as private and supplies generic public
  lock-screen content. When Android's user-controlled visibility settings show
  private content, it may include the client, request type, command, and profile
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
  private keys for the device identity and pairings, and stored profile
  material, cannot be decrypted on this device.
- Counts of affected profiles, clients, and outstanding requests.
- **Set up this device again** as the main recovery action.
- After replacement, a checklist to replace unavailable Variables and SSH
  keys, reconfigure affected credential issuers, and pair clients again.
- A link to Data and history explaining backup behavior.

This state must not hide the main application. Requests and metadata remain
readable; profiles with unavailable material are marked in place. Profile
access and pairing continuation fail closed until replacement is complete.
**Set up this device again** reuses the Device setup and Claim pairing address
screens in recovery mode rather than introducing another recovery editor.

## Requests

### R-01 — Request list

**Surface:** Primary destination and list screen.

**Purpose:** Be both the application home and the authoritative, durable inbox
and history for operations that require a user decision or policy evaluation.

Purely read-only or automatically handled operations do not appear here merely
because they arrived from a client. Profile list access, automatic
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

- one operation-specific title: **Pairing request**, the reported command for
  Profile access, or the proposal mode and Profile name such as **Update
  aws-read-only**;
- current status;
- the Client's friendly name as supporting text, falling back to reported
  hostname and then an unknown-Client label; and
- received time.

A Profile access row also shows a compact Profile-name summary when it fits the
phone row, such as one name plus a count of additional Profiles. It never shows
values. A completed row may show a compact automatic-decision marker; the full
decision source, policy version, and delivery state belong to detail. The
operation title makes a redundant **Request:** or **Client:** prefix
unnecessary.

Status language must distinguish **Needs approval**, **Needs review**, **Waiting
for Client**, **Delivered**, **Accepted**, **Rejected**, **Denied**,
**Cancelled**, **Expired**, **Unconfirmed**, and **Verification failed** as
appropriate to the Request type. “Completed” alone is too vague for security
history.

Empty state shows whether new pairings are accepted. When they are accepted, it
shows the current pairing address, a copyable pairing command, and links to
create the first Profile and view pairing instructions. When they are paused,
it instead links to Device & pairing to resume them.

The list must not automatically open a new request or place a modal over the
current screen. Several requests remain independently navigable.

All non-terminal requests are retained regardless of the history limit.
Terminal requests are kept in reverse arrival order up to a fixed product
limit; pruning removes the oldest terminal rows only. The exact limit remains
an open product decision. Request history participates in normal Android backup
and device transfer and is independent of Audit log retention.

### R-02 — Common request detail structure

**Surface:** Shared content within every Request detail screen; not a separate
screen.

Every request detail starts with:

- an operation-specific title and a prominent, plain-language status;
- **Client** with the linked friendly name, plus separately labeled reported
  hostname when known;
- received time and any current expiry or response deadline;
- a short type-appropriate lifecycle timeline that distinguishes receipt,
  review or policy evaluation, the resulting action, and any confirmation still
  expected;
- decision source when applicable and, for a policy, its name and version;
- any safe failure reason;
- the relay request ID and client ID under technical details; and
- **Delete from history** only for terminal requests.

The title does not repeat **Request:**. Primary content is the status, the
operation being authorized or reviewed, the Client, and any available action.
Lifecycle and decision information is supporting content. Relay Request ID,
Client ID, decided/completed/last-updated timestamps, versions, and
cryptographic diagnostics remain under Technical details.

Deletion removes the local history entry, not profiles, policies, or an active
client. Active or unsettled requests cannot be deleted. Existing Audit log
events about the request remain and show that the detailed request record is no
longer available.

Fields are grouped by trust:

1. **Agentknock state** — request IDs, cryptographic verification, lifecycle,
   decision, and delivery confirmation.
2. **Reported by client** — hostname, platform, command, executable, and
   working directory. These are authenticated as the paired client's report
   but not independently attested.
3. **Reason reported by Client** — optional, untrusted text supplied with the
   Request.

The warning about reported data should be concise and visible without making
the useful request data unreadable.

### R-03 — Pairing request detail

**Surface:** Pairing variant of the Request detail screen.

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
- After activation: link to the new Client detail.

Architecture, operating-system version, machine ID, CLI version, Client ID,
and pairing address used remain available under Reported information or
Technical details; they do not compete with the SAS decision.

Current product behavior admits only one incomplete pairing. An existing
pending pairing remains in the list until the user accepts or rejects it.
Pausing new pairings does not cancel a pairing that the relay already admitted;
that Request remains actionable.

The screen title is **Pairing request**. The proposed Client name is a primary
editable value, not the screen title, because the pairing has not created a
Client yet. Reported hostname and other machine information retain labels that
make their reported origin clear.

### R-04 — Profile access request detail

**Surface:** Profile access variant of the Request detail screen.

**Purpose:** Let the user make an informed decision about one atomic set of
requested capabilities without revealing confidential material.

**Show:**

- The exact requested profile names, descriptions, concrete types, and public
  delivery contracts.
- For an Environment variables profile, every Variable name; for AWS temporary
  credentials, the account, role, lifetime, and fixed environment variables it
  provides; and for an SSH key, its algorithm, public-key fingerprint, and
  delivery helper.
- Missing profiles, unavailable material, invalid provider configuration, and
  conflicting provided environment variables as blocking errors.
- A reminder that approval authorizes the complete displayed set atomically;
  partial approval is unavailable.
- The reported command and each argument as separate structured values.
- Optional Reason reported by the Client, visually separated from the
  operation.
- Reported Client identity: friendly name and hostname.
- Request timing and any expiry or cancellation state.
- The decision mode: manual, matching phone-held policy, or later remote
  reviewer under a named policy.

A supporting **Execution details** section shows working directory, executable
path and mode, SHA-256 identity when reported, standard-stream kinds, and
launcher chain. Reported platform, architecture, OS version, machine ID, and
CLI version remain in the linked Client or under Reported information. These
fields stay reachable but do not crowd the Profile, command, and decision
actions.

Manual pending state always provides:

1. **Deny once** — deny only this Request.
2. **Approve once** — approve only this Request without creating a policy.

When the Client has reported the executable identity required for safe reuse,
it also provides:

3. **Approve this command with these arguments for 4 hours** — approve this
   Request and create a temporary policy for the exact client, requested
   Profile set, command, and ordered argument vector.
4. **Approve this command with any arguments for 4 hours** — approve this
   Request and create a temporary policy for the exact client, requested
   Profile set, and command while leaving the argument vector unrestricted.

The reusable actions are unavailable when the Request lacks a resolved
executable path or executable hash. The screen explains that one-time approval
is still possible but Agentknock cannot safely recognize the same executable
for later Requests.

The four-hour duration is an editable default. Either temporary-policy action
opens the Policy editor defined in A-03, prefilled with the selected argument
mode. The user can change the duration and review the exact expiry and scope
before one final save-and-approve action. The unrestricted argument choice is
visually distinguished as broader. No prefix, pattern, partial-argument, or
indefinite matching mode is offered initially.

Every approving action revalidates the Request and every Profile's type,
configuration, contents, and availability before responding. If anything
changed since the screen was rendered, the decision stops and the updated
details must be reviewed again. Creating the temporary policy and recording
the current decision must not produce a duplicate or broader policy if delivery
is retried. The current Request records the user as its decision source and
links the newly created policy; only later matching Requests identify that
policy as their automatic decision source.

Terminal states must separately report:

- allowed and use or delivery confirmed;
- allowed but use or delivery unconfirmed;
- denied and client confirmation state;
- cancelled or expired before a response;
- invalid request or cryptographic verification failure; and
- an automatic decision, including the exact policy/reviewer version.

Additional policy detail, if offered, remains within that editor. There is no
second unnamed confirmation screen, and the editor never creates or widens a
policy without authenticated review.

The reported command is the primary operation title when present; otherwise
the title is **Profile access**. **Profiles**, **Client**, **Command**,
**Arguments**, **Working directory**, and **Reason reported by Client** are
labeled because each has a distinct role in the decision. The common Request
status is not repeated in every group. Each requested Profile is identified by
name without a **Profile:** prefix; its type and delivery contract are attached
supporting information. Complete Variable or provided environment-variable
names remain available within that Profile's request detail without becoming
headline fields.

### R-05 — Profile proposal request detail

**Surface:** Profile proposal variant of the Request detail screen.

**Purpose:** Let the user review a Profile uploaded by a Client before it
changes the Device's Profile collection.

**Show:**

- the proposing Client and received time;
- the proposal mode: **Create**, **Replace**, or **Update**, with a concise
  explanation of what that mode changes;
- proposed Profile name, description, and concrete type;
- for an Environment variables proposal, every Variable name with its value
  masked;
- a change summary that clearly separates added, changed, removed, and
  unchanged fields and Variables; and
- the existing target Profile for Replace or Update, with a link to its detail.

Create proposes a new Profile and allows its name to be edited before
acceptance. Replace and Update target one existing Profile by name; the target
name and immutable type cannot be changed during review. Replace proposes the
complete new contents, including removals. Update changes only the supplied
fields and Variables.

Every newly added Variable is marked **Sensitive** by default. When Replace or
Update supplies a new value for a Variable that already exists under the same
name, that Variable keeps its existing sensitivity setting. An upload never
silently changes an existing Variable from non-sensitive to sensitive or vice
versa merely because its value changed. The user can change sensitivity later
from the accepted Profile's Variable editor.

Actions are **Accept proposal** and **Reject proposal**. The acceptance review
states the resulting Profile name and summarizes removals before confirmation.
Accepted and rejected proposals remain in Request history with the exact mode
and safe change summary, but never retain displayable values there.

A proposal that targets a missing Profile, conflicts with an existing name,
uses a different type from its Replace or Update target, or is otherwise
invalid cannot be accepted. Such a proposal is rejected without becoming an
actionable Request and is recorded in the Audit log with a safe reason.

The Client completes upload after the Device has durably received a valid
proposal; it does not wait for the later user decision. The detail therefore
distinguishes **Received for review** from **Accepted** and **Rejected**, and
does not imply that the original Client will receive the eventual decision.

The title combines mode and name, such as **Create bootstrap** or **Update
aws-read-only**, rather than **Profile proposal: aws-read-only**. The concrete
type is supporting information. Change categories and affected Variable names
are labeled; individual unchanged fields need not occupy the primary summary.

## Audit log

The Audit log answers “what happened?” independently of whether an operation
required authorization. It is a secondary, security-oriented screen under
**Settings > Data and history**, not another primary navigation destination.

Requests and audit events are separate records. One request produces several
audit events as it moves through receipt, decision, response, and confirmed or
unconfirmed delivery. An operation that requires no decision produces audit
events without creating a Request row.

### L-01 — Audit log list

**Surface:** List screen under Data and history.

**Purpose:** Present a chronological record of security-relevant client,
device, user, and policy activity.

Record at least:

- Profile list access and its outcome;
- request receipt, user or policy decision, response, expiry, cancellation,
  and delivery confirmation;
- Profile proposal receipt, acceptance, rejection, and validation failure;
- pairing activation, rejection, failure, and client-initiated unpairing;
- client rename, requested and confirmed suspension or resumption,
  reauthorization, and revocation;
- pairing-address claim and replacement, and pausing or resuming new pairings;
- Profile, Variable, SSH key, issuer configuration, and policy creation,
  change, replacement, and deletion;
- device setup, recovery, and protected-key availability changes; and
- security-relevant authentication, cryptographic, and protocol failures.

Do not treat routine connection changes, screen navigation, rate or capacity
backoff, or diagnostic messages as audit events. Connection and retry history
belongs in Connection diagnostics. The Audit log records product and security
actions, not internal events.

Each reverse-chronological row directly shows:

- a concise event-and-outcome title, such as **Client suspended** or **Profile
  proposal rejected**;
- timestamp; and
- up to two supporting identities: actor and affected object, using their names
  without redundant type prefixes when context is already clear.

A compact indicator shows when a retained Request detail is available. Exact
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
- related request, client, profile, or policy links when those records still
  exist;
- delivery or client-confirmation state when applicable;
- a sanitized failure category; and
- app, CLI, and protocol versions when relevant.

The event-and-outcome phrase is the title; it is not prefixed with **Audit
event:**. Actor and affected-object relationships are labeled when both appear.
Friendly snapshots are primary supporting data, while stable IDs and versions
remain under Technical details.

An audit event retains enough names and safe metadata to remain intelligible
after the live object or bounded Request record is deleted. It never contains
secret values, generated temporary credentials, private keys, device or client
tokens, encrypted blobs, raw messages, commands, Reasons, or other fields that are
not necessary to identify the event.

### L-03 — Profile list access events

**Surface:** Profile-list-access variant of the Audit event detail screen.

**Show:**

- the requesting client and its reported information;
- event phase: operation received, Profile list sent, delivery confirmed,
  or failure;
- the number, names, and concrete types of profiles returned, captured on the
  operation-received event;
- the operation's stable correlation ID and links to its other audit events;
  and
- sent, delivered, unconfirmed, or verification-failed outcome as known at the
  time of this event.

The detail states that Profile list access is automatic but audited because
names and usage patterns may be sensitive metadata. Descriptions, values,
provided environment-variable names, and private-key material are not copied
into the Audit log.

### L-04 — Profile proposal events

**Surface:** Profile-proposal variant of the Audit event detail screen.

**Show:**

- the proposing Client, proposal mode, Profile name, and concrete type;
- received, accepted, rejected, or validation-failed outcome;
- a safe summary of which fields and Variable names were added, changed, or
  removed when useful;
- the resulting Profile link after acceptance;
- the related Request link when a valid proposal reached user review; and
- a safe rejection category for a missing target, name conflict, type mismatch,
  or invalid proposal.

Values are never copied into the Audit log. A proposal validation failure may
have Audit events without a Request because it never became actionable.

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
not impersonate a user-facing authorization Request. A Client's force-remove
operation changes only that Client's local state, so the Device cannot show or
audit it unless the Client later communicates with the Device again.

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
backup and device transfer independently of whether protected profile material
remains decryptable.

## Profiles

Profiles use one flat, global namespace. A name therefore identifies a profile
without a separate type qualifier: `aws-read-only` cannot simultaneously name
an Environment variables profile and an SSH key profile. The type is immutable
after creation. Profiles are not nested and do not share child objects;
composition happens in a request, such as:

```text
agentknock exec --profile aws-read-only --profile production-ssh -- command
```

`-p` is the shorthand for each `--profile` occurrence.

### P-01 — Profile list

**Surface:** Primary destination and list screen.

**Purpose:** Browse and manage the typed capabilities exposed to clients.

Each row directly shows:

- Profile name as the primary text, without a **Profile:** prefix;
- concrete type;
- availability or attention state; and
- one compact type-specific summary, such as a Variable count, AWS account and
  role, or abbreviated SSH fingerprint.

Description, complete provided environment-variable names, delivery mechanism,
created and updated times, last request and outcome, active policies, and any
high-value classification belong to Profile detail. A compact policy or
high-value marker may appear in the row only when it changes how the user
should interpret the Profile.

Primary action: **New profile**.

Empty state explains that each profile supplies one kind of capability and
that a command can request several profiles together. Examples should include
an Environment variables profile, AWS temporary credentials, and an SSH key.

### P-02 — New profile and type selection

**Surface:** Profile creation editor screen.

**Show:**

- globally unique profile name;
- optional description; and
- one concrete type: **Environment variables**, **AWS temporary credentials**,
  or **SSH key**.

The type choice determines the configuration form, the environment variables
it provides when applicable, and delivery behavior. There is no generic
“dynamic” type with a separate provider selector: provider-specific semantics
belong to types such as AWS temporary credentials. A saved profile cannot
change type; the user creates a new profile instead.

Validate empty names, leading or trailing whitespace, and global uniqueness
before opening or saving the type-specific editor.

The screen title is **New profile**. Because no Profile name has yet been
established as a title, **Name**, **Description**, and **Type** are explicit
field labels. Selecting a type continues within the creation flow; it does not
create a new Settings or primary-navigation destination.

### P-03 — Profile detail

**Surface:** Detail screen for one Profile.

Every profile detail shows:

- name, description, immutable concrete type, created time, updated time, last
  requested time, and aggregate request count;
- availability and any type-specific configuration error;
- its public delivery contract and, where applicable, the fixed environment
  variables it provides;
- active policies that reference the profile; and
- recent authorization Requests and audit events filtered to this profile,
  linking to their normal details.

The detail then presents the type-specific information defined below. Stored
values and private keys remain masked. Generated temporary credentials are not
exposed or retained merely because a request completed.

Profile actions are **Edit**, **Rename**, and **Delete**, plus type-specific
actions such as replacing stored material or testing issuer configuration.
Renaming changes the CLI-facing handle but preserves the stable internal ID,
policies, and history. Deleting makes future requests by that name fail;
history retains the profile name, type, and request metadata, never values.

Saving, renaming, and deleting are security-sensitive management actions and
use the device's configured authentication controls.

The Profile name is the screen title. Type, availability, and description are
primary or supporting information directly below that identity without
repeating **Profile name**. Created, updated, and usage timestamps are labeled
because their meanings differ. Stable Profile ID, if ever exposed, belongs
under Technical details.

### P-04 — Environment variables profile

**Surface:** Type-specific content within Profile detail, plus a separate
Variable editor screen for Add variable and Edit variable.

**Purpose:** Manage a cohesive set of fixed environment variables that must be
released together.

The Profile detail directly shows:

- one row per Variable with its environment-variable name as primary text;
- sensitivity and availability for each Variable; and
- type-level last-used state when useful.

Created, updated, and value-updated times and notes are available after opening
that Variable rather than filling every Profile-detail row. The Variable editor
shows labeled **Name**, **Value**, **Sensitive**, and **Notes** fields, plus the
existing Variable's lifecycle timestamps as read-only supporting information.
The value remains masked until the user deliberately reveals or replaces it.

An existing Variable editor uses the environment-variable name as its title;
the add flow uses **New variable**. Neither needs a **Variable:** title prefix.

Each Variable owns its fixed environment-variable name and value. The app must
never reinterpret an existing value under a different name or sensitivity
setting. If the value is unavailable, changing either property requires
replacing the value. Non-sensitive Variables are permitted because not every
value, such as `AWS_REGION`, is confidential; all values remain encrypted at
rest regardless. Changing a Variable's value does not change its sensitivity.

Actions are **Add variable**, **Edit variable**, and **Delete variable**.
Editing can reveal or copy a value only after device authentication when its
sensitivity requires it. The editor never substitutes an empty value and does
not silently merge duplicate names.

### P-05 — AWS temporary credentials profile

**Surface:** Type-specific content within Profile detail, plus an editor screen
for its AWS configuration.

**Purpose:** Configure one AWS temporary-credential provider whose generated
environment variables share one lifetime and must remain synchronized.

The Profile detail directly shows:

- AWS account and role;
- the fixed list of environment variables provided to the Client, including
  `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN`, plus
  optional names such as `AWS_REGION` when the type supports them;
- configured lifetime;
- availability of any long-lived authorization material, without revealing
  it; and
- last generation time, outcome, and safe failure category.

The editor shows the labeled authorization, scope, account, role, and lifetime
inputs required by this concrete type. Provider-specific technical identifiers
that are not needed to recognize the account or role remain secondary.

AWS is inherent in this profile type; there is no separate provider flag. One
request produces the full credential set atomically with one expiry. The same
fixed list of provided environment-variable names is visible on the Device and
in the CLI's Profile list. Generated values are retained only as long as
delivery and audit correctness require and do not become durable stored
Variables.

### P-06 — SSH key profile

**Surface:** Type-specific content within Profile detail, plus focused Generate
key, Import key, and Replace key editor screens.

**Purpose:** Manage one SSH key and expose it through an SSH-specific helper.

The Profile detail directly shows:

- key algorithm, public key, fingerprint, and comment;
- whether private-key material is available;
- whether the key was generated on the device or imported;
- the configured helper/delivery behavior once that behavior is defined; and
- created, updated, material-updated, and last-used times.

Fingerprint and availability are the primary key summary. Algorithm, comment,
and public key are labeled supporting fields. Lifecycle timestamps and delivery
details remain secondary; private-key material is never a display field.

Generate key shows the selectable supported algorithm, optional public comment,
and the fact that a new private key will be created on this Device. Import key
shows the detected algorithm, public-key fingerprint, and validation result
before saving. Replace key shows both the current and proposed public
fingerprints and clearly states that future access uses the replacement. These
editor screens never render private-key bytes after input.

Actions are **Generate key** or **Import key**, **Replace key**, and
type-appropriate public-key copy actions. Private-key material remains hidden
unless a later explicit export feature is designed. The profile has no
environment variables and cannot contain multiple keys.

Stored confidential profile material remains encrypted at rest. If it is
unavailable after restore or transfer, metadata and history remain visible,
but the material must be replaced before the profile can be used or its bound
properties changed.

## Clients

### C-01 — Client list

**Surface:** Primary destination and list screen.

**Purpose:** Manage the installations that can ask this device for profile
metadata or values.

Each row directly shows:

- user-assigned friendly name as primary text, falling back to reported
  hostname and then an unknown-Client label;
- state: active, suspending, suspended, resuming,
  stale/reauthorization required, security attention, revoking, revoked, or
  keys unavailable on this restored device;
- reported hostname or compact platform summary when it adds information beyond
  the friendly name; and
- last activity time.

A pending-Request indicator appears when nonzero because it requires attention.
Paired time, full platform information, active-policy count, and exact Request
counts belong to Client detail. Rows use **my laptop**, not **Client: my
laptop**.

Default view prioritizes active and attention-requiring clients. A
**Previous** filter exposes revoked clients without mixing them into the
normal active list.

Empty state shows whether new pairings are accepted. If they are, it shows the
pairing address and a copyable
`agentknock pairing start <PAIRING_ADDRESS>` command. If they are paused, it
links to the control that resumes them. Pending pairing attempts link to their
Request detail rather than becoming half-created client rows.

### C-02 — Client detail

**Surface:** Detail screen for one Client.

**Show:**

- friendly name, editable by the phone user;
- authoritative Agentknock state and any security warning;
- reported hostname, platform, architecture, OS version, machine ID, CLI
  version, and client ID;
- paired, last authorization Request, last successful Request, last Profile
  list access, and last seen times when available;
- continuity-key rotation status in user language: healthy, stale,
  reauthorization required, or competing copy detected;
- active approval policies for this client;
- authorization Requests and audit events filtered to this client; and
- an explanation that reported machine information is not independent host
  attestation.

Actions:

- **Rename**;
- **Suspend** or **Resume**;
- **Reauthorize** when stale or after a copied-state warning;
- **Revoke client**; and
- **View approval policies** when policies are available.

The Client remains **Suspending**, **Resuming**, or **Revoking** until the relay
confirms the requested state. These transitions survive leaving the screen or
restarting the app and remain visibly retryable after a delivery failure.

Once suspension is confirmed, the Client cannot send new traffic and its
pairing is retained for later resumption. A Request already delivered to the
Device remains visible and can still be decided, but its response cannot reach
the Client until the Client resumes and reconnects; it may expire first.
Suspension does not revive messages already discarded or Requests already
expired.

Revocation is authoritative and permanent for that pairing: future Requests
fail, outstanding exchanges and delayed responses are terminated, the CLI must
pair as a new Client, and local history remains.

A competing-copy warning automatically suspends policies for that client
until the user explicitly reauthorizes or revokes it.

The friendly name is the screen title. State and any security warning are
primary. **Reported hostname**, **Platform**, **Architecture**, **OS version**,
and **Machine ID** remain explicitly labeled because they are Client-reported
attributes, while **Client ID** and CLI/protocol versions belong under
Technical details. Related policies, Requests, and Audit events are named
sections rather than fields competing with the Client identity. **View approval
policies** opens the existing Policy list filtered to this Client; it does not
create a Client-specific policy screen.

### C-03 — Client reauthorization

**Surface:** Focused action screen opened from Client detail only when the
Client is eligible for reauthorization.

**Purpose:** Let the user deliberately restore trust after stale continuity
state or a competing-copy warning.

**Show:**

- the friendly Client name and current warning;
- the reported hostname and platform snapshot being reauthorized;
- why reauthorization is required and what evidence the user must compare or
  confirm once that protocol is defined;
- progress and any expiry while reauthorization is incomplete;
- the effect on suspended approval policies; and
- **Cancel**, **Reauthorize**, and **Revoke client** only in the states where
  each action is valid.

The screen title is **Reauthorize my laptop**, substituting the friendly name,
not **Reauthorize Client: my laptop**. Technical identifiers and protocol
transcript state remain secondary. Until a concrete verification contract
exists, the UI must not substitute a generic confirmation that treats reported
machine metadata as proof.

## Approval policies and automation

Automatic approval is a later product capability, but its screen boundaries
should be reserved now so that it does not become a loose collection of toggles.

### A-01 — Approval policy list

**Surface:** List screen reached from Automation and contextual policy links.

**Purpose:** Inspect and stop reusable phone-held authorization.

**Show:**

- master state: automatic approvals enabled or paused;
- each policy's name as primary text, enabled/paused/expired status, concise
  Client/Profile scope, and expiry;
- prominent warnings for policies suspended by client competition,
  unavailable profile material, invalid issuer configuration, or subscription
  entitlement; and
- a link to every automatic decision through normal request history.

Last match, recent match count, complete executable scope, and version belong to
Policy detail. A row uses the policy name without a **Policy:** prefix.

The initial creation path starts from a pending Profile access Request so the
app can capture and show a concrete scope. A global **Pause automatic
approvals** action is fast and reversible; it does not delete policy
definitions.

### A-02 — Policy detail

**Surface:** Detail screen for one Policy.

**Show:**

- policy name, stable identifier, and current version;
- enabled, paused, expired, or blocked state and reason;
- exact client selector;
- exact profile or profile-set selector and how profile configuration changes
  affect matching;
- original command name, resolved executable path, and executable-content
  identity used to recognize the same command version;
- argument mode: exact ordered arguments or any arguments;
- start, expiry, created, updated, and last-used times;
- whether matching requests are approved deterministically, sent to a remote
  reviewer, or escalated for manual review;
- high-value profile restrictions;
- automatic decision count and recent matching requests; and
- what request data a remote reviewer may see, if applicable.

Actions: **Pause/enable**, **Edit**, **Revoke**, and **View matching activity**.
Revoke prevents future matches but cannot retract material already delivered
or operations already completed.

**View matching activity** opens the existing Request list filtered to this
Policy rather than a separate activity screen.

The policy name is the screen title and current status is primary. **Client**,
**Profiles**, **Command**, **Arguments**, **Decision mode**, and **Expires** are
labeled scope fields. Stable identifier, version, exact hash, and timestamps
other than expiry and last use remain under Technical details or a secondary
history section.

### A-03 — Create or edit a temporary policy

**Surface:** Policy editor screen opened from a pending Profile access Request
or an existing Policy detail.

**Show and require review of:**

- a generated, editable user-visible policy name;
- the one exact client copied from the Request;
- the exact profile set and whether authorization-relevant changes to a
  selected profile require manual review;
- the original command name, resolved executable path, and SHA-256 executable
  identity copied from the Request;
- argument mode: the exact ordered argument vector copied from the Request, or
  any arguments;
- an editable duration defaulting to four hours and the resulting absolute
  expiry time;
- decision mode;
- what happens when a field does not match: manual review or deny; and
- a final natural-language summary with representative matching and
  non-matching examples.

The exact-arguments comparison operates on the structured ordered argument
vector, not a rendered shell command. **Any arguments** removes only that
comparison; it does not broaden the client, Profile set, original command name,
resolved executable path, or executable contents. A different order of the
same requested Profile names still represents the same set. Launcher-chain
information remains visible as Client-reported context but does not broaden or
narrow the initial policy match. A Profile change with authorization
consequences fails policy matching and returns the Request to manual review.

A reusable policy cannot be created from a Request that lacks the resolved
executable path or SHA-256 identity. The normal UI can describe the hash as the
same executable version; the exact value remains available in technical
details.

The safe default is no policy. The editor must not offer selectors whose match
semantics are not implemented and testable. A client or agent cannot create,
widen, extend, or make a policy indefinite. **Deny once** and **Approve once**
never create policy records.

Saving, widening, enabling, or extending a policy requires device
authentication. The stored version increments whenever authorization-relevant
fields change so history can identify the exact rule that acted.

The create flow uses a **New approval policy** title and a labeled **Name**
field. Editing an existing Policy uses its policy name as the title without a
**Policy:** prefix.

### A-04 — Remote reviewer setup

**Surface:** Focused Automation setup screen; not a primary destination.

**Status:** later, potentially paid.

**Show before enabling:**

- the difference between phone-held deterministic policy and remote LLM
  review;
- exact request fields sent to the provider;
- that no secret values, generated temporary credentials, private keys, or
  decryption keys are sent;
- eligible policies and the fallback on timeout, provider failure, or
  uncertainty;
- current subscription entitlement and expected usage limits; and
- an explicit link to disable the reviewer without deleting policies.

The user chooses this trust mode deliberately. It must never appear to be a
more convenient spelling of local automatic approval.

## Settings and secondary screens

### T-01 — Settings overview

**Surface:** Settings destination screen.

**Purpose:** Present infrequently changed application behavior and status.

Rows show their current state in supporting text and open focused subscreens:

- **Device & pairing** — current pairing address, whether new pairings are
  accepted, or recovery required.
- **Notifications** — enabled, disabled, or delivery setup needs attention.
- **Security** — device protection and local-key status.
- **Automation** — off, paused, or number of active policies.
- **Data and history** — backup, bounded request history, and Audit log.
- **Plan and billing** — Free, paid plan name, payment attention, or unavailable.
- **Connection diagnostics** — connected, offline, or last error.

Help and About are secondary destinations at the end, not mixed with behavior
switches. Production Settings must not expose relay URLs, retry intervals,
cryptographic algorithms, or other implementation knobs.

### T-02 — Device & pairing

**Surface:** Settings sub-screen.

**Show:**

- pairing address with copy/share action;
- ready, incomplete claim, or keys-unavailable state;
- device ID under technical details;
- device-identity creation time and current-address claim time;
- new-pairing state: **Accepting new pairings** or **New pairings paused**;
- pairing instructions and link to Clients;
- **Pause new pairings** or **Resume new pairings**;
- **Change pairing address**; and
- replacement device-identity setup when keys are unavailable.

Pausing affects only previously unknown pairing attempts. It keeps the pairing
address, does not cancel an already admitted pairing Request, and remains in
effect until the user explicitly resumes it; there is no automatic timeout.

Address change must explain its exact effect on existing clients before
confirmation: once the replacement is claimed, every existing Client must pair
again and its approval policies can no longer authorize access. Profiles and
history remain. Address replacement is not shown as complete until the required
Client revocations are confirmed. The working address remains available until
its replacement has been claimed successfully, and an interrupted change
remains resumable.

Never display or export the device token or private device keys.

The screen title is **Device & pairing**. **Pairing address** and
**New pairings** are labeled primary fields because neither is a user-assigned
object name. **Device ID** is labeled under Technical details. Claim and key
states use concise status text without exposing transport terminology.

### T-03 — Notifications

**Surface:** Settings sub-screen that links to Android notification system
surfaces where Android owns the choice.

**Show:**

- Android notification permission and channel state;
- relay push registration state: registered, missing, invalid, or temporarily
  unavailable;
- last successful push registration and last generic wake, when known;
- **Enable notifications** or **Open system notification settings** as
  appropriate;
- whether actionable Request notifications are available on this device;
- whether automatic approvals generate informational notifications, once that
  feature exists; and
- **Test notification** only if it can exercise the real production path
  without creating misleading request state.

Do not duplicate Android's channel controls. Link to system settings for sound,
vibration, lock-screen visibility, and complete enable/disable behavior.

The effective notification state and the action needed to change or repair it
are primary. Relay registration state appears directly only when it needs
attention; registration and wake timestamps are supporting diagnostics rather
than top-level settings rows.

An actionable notification appears only after the app has retrieved and
authenticated the Request. A generic push wake can never authorize anything.
Agentknock supplies generic public lock-screen content and marks Request details
as private. Subject to Android's user-controlled visibility settings, private
content shows enough context to distinguish the client, request type, command,
and Profiles without showing confidential material or a Reason reported by the
Client.

Pending Profile access notifications initially provide **Deny once** and
**Approve once**:

- **Deny once** applies only if the same Request is still pending and is safe to
  repeat without changing the outcome.
- **Approve once** requires device authentication and the same complete
  revalidation as approval in the app. If Android cannot provide the required
  authenticated interaction from the notification, the action opens the exact
  Request detail to finish approval.

Creating a temporary policy remains an in-app action initially. Its broader
scope, argument mode, Profile set, and editable expiry require the focused
review surface. Dismissing a notification has no effect on its Request, and a
notification is removed or updated when the Request resolves elsewhere.

A Profile proposal notification identifies its Client, mode, and Profile name
and opens the proposal detail for review. It does not offer an acceptance
shortcut before the user has seen the proposed changes.

### T-04 — Security

**Surface:** Settings sub-screen.

**Show:**

- secure screen-lock availability;
- local encryption-key state and whether hardware-backed protection is in use
  when Android can report it reliably;
- a plain explanation that hardware backing is best effort and the trusted
  phone OS can use protected material while authorized;
- number of profiles with sensitive or unavailable material;
- clients with stale or competing state; and
- links to manage step-up requirements if that later feature is enabled.

This screen reports and explains security posture; it does not manufacture
low-value security toggles. Android-owned screen lock and biometric enrollment
open the relevant system settings.

The overall usable/attention-required state and any corrective action are
primary. Hardware-backing detail, counts, and explanatory limitations are
supporting information rather than separate status cards for every mechanism.

### T-05 — Automation

**Surface:** Settings sub-screen and entry point to the Policy list.

**Show:**

- master automatic-approval state;
- count of active, paused, expired, and blocked policies;
- default behavior for unmatched requests, which is manual review unless a
  future explicit deny mode is selected;
- remote-reviewer state and provider-visible-data summary when applicable; and
- link to the policy list.

The master control pauses evaluation. Policy creation and detailed scoping stay
in the policy workflow rather than becoming nested preference rows.

The automatic-approval state and link to policies are primary. Counts and
remote-reviewer details are supporting summaries and appear only when those
features exist.

### T-06 — Data and history

**Surface:** Settings sub-screen and entry point to the Audit log and Factory
reset.

**Show:**

- local counts for profiles, Variables, clients, Requests, audit events, and
  policies;
- what Android backup/device transfer preserves: metadata and encrypted stored
  material, including Request history and the Audit log;
- what it cannot preserve: device-bound private keys, so restored profile
  material and pairings may be unavailable;
- that the relay removes a Device after 180 days without authenticated Device
  activity; Client traffic and push attempts do not by themselves keep that
  Device registered;
- the terminal Request history limit and current count;
- **Audit log**, showing its one-year retention period and oldest retained
  event;
- **Clear completed request history** with count and confirmation; and
- **Factory reset Agentknock**, visually separated from ordinary data and
  history controls as an irreversible recovery and deletion action.

Clearing history never deletes active clients, profiles, policies, or
unsettled Requests. It also does not delete Audit log events about pruned or
manually deleted Requests. Individual audit events cannot be deleted; normal
retention removes expired events, and Factory reset removes the log with
everything else. Export is omitted until its privacy model and format are
defined.

Audit log, backup/restore meaning, and the two available cleanup actions are
the primary content. Object counts and retention dates are grouped supporting
information rather than six competing headline values. Factory reset remains
visually isolated from Clear completed request history.

### T-07 — Factory reset Agentknock

**Surface:** Focused action screen reached manually from Data and history.

**Purpose:** Give the user a deliberate way to erase Agentknock completely and
recover by starting again when the current Device can no longer be used.

This is a dedicated full-screen destructive flow, not a routine confirmation
dialog. It is manually reachable from Data and history even while the relay is
unavailable.

**Show before reset:**

- an unmistakable **This cannot be undone** warning;
- every local category that will be erased: Device identity and keys, Profiles
  and values, Clients, Requests, policies, Audit log, and settings;
- the remote state Agentknock will attempt to delete: the relay Device, Client
  registrations, pending exchanges, and push registration;
- that deletion removes live relay state while any limited infrastructure
  recovery retention follows the published privacy policy;
- that every Client must pair again;
- that setup starts from the beginning with a new Device identity and pairing
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

Agentknock first asks the relay to delete the Device. If deletion is confirmed,
it erases all local state and returns to initial setup. If the relay
unambiguously confirms that the Device is already absent, the same local reset
may continue after the full confirmation.

If remote deletion cannot be confirmed because the relay is unreachable or
rejects the operation, nothing is erased automatically. The screen offers
**Try again** and **Cancel**. A separately confirmed **Reset this app anyway**
remains available for recovery, with an explicit warning that inaccessible
remote state may remain until the relay's inactivity cleanup removes it.

Factory reset is never suggested, preselected, or opened in response to a
connection, synchronization, authentication, or protocol error. In particular,
one or many `404` responses, regardless of duration, are not proof that the
mailbox was permanently deleted. Such failures retain all local state and use
ordinary retry behavior. If the mailbox truly is gone or permanently
inaccessible, Factory reset remains a manual recovery path rather than an
automatic diagnosis.

Using Android's system **Clear storage** action erases only local app data and
cannot request relay deletion. Help and this screen explain that distinction.

### T-08 — Connection diagnostics

**Surface:** Settings sub-screen reached directly or from a contextual status
icon or error action.

**Show:**

- current live-connection state and why it is or is not expected to be
  connected;
- last connected, disconnected, caught-up, and successful-sync times;
- last sanitized connection or synchronization error;
- current retry state and the next retry time when the relay provides one;
- rate-limited or temporarily-capacity-limited state when observable, without
  exposing internal quotas as user settings;
- count of pending incoming and outgoing work;
- device claim state;
- push registration and Android notification state;
- service endpoint and app version under technical details; and
- copyable, secret-free diagnostic summary.

Actions: **Reconnect/synchronize now**, **Retry push registration**, and links
to resolve the specific system setting. Never include device/client tokens,
private keys, encrypted profile material or values, full request payloads, or
Reasons reported by Clients in copied diagnostics.

Retryable rate and capacity failures use ordinary backoff and must not be shown
as authorization denials. Relay-internal administrative causes are not named or
given dedicated UI; only their observable availability or delivery effect is
shown.

Current state, whether the app will retry, and an available corrective action
are primary. Historical timestamps, queue counts, endpoint, version, and the
copyable diagnostic summary are secondary or technical details.

### T-09 — Plan and billing

**Surface:** Settings sub-screen.

**Purpose:** Provide a stable home for a future monthly subscription without
making billing a primary application destination.

For a free user, show:

- current plan: Free;
- a capability comparison driven by actual product entitlements, not hardcoded
  marketing promises;
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

The current plan name, entitlement state, applicable price, and available
action are primary. Billing lifecycle terminology and dates are labeled
supporting information. The screen title remains **Plan and billing** rather
than repeating **Plan:** before the plan name.

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
misrepresented as a profile access denial or cryptographic failure.

### T-11 — Help

**Surface:** Settings sub-screen.

**Show:**

- getting started: create a profile, pair a client, execute, and review;
- exact examples for `agentknock pairing start <PAIRING_ADDRESS>`,
  `agentknock pairing finish`, `agentknock profile list`, Profile upload, and
  `agentknock exec`, using the current pairing address where applicable;
- troubleshooting links for offline requests, notification problems, broken
  pairing, unavailable restored values, Factory reset, and subscription state;
- concise security-boundary explanation; and
- support and security-reporting links once publication details exist.

Help examples never include real stored values or request data.

### T-12 — About, privacy, and licenses

**Surface:** Settings sub-screen.

**Show:**

- app name and version;
- source-code link and license once selected;
- privacy policy, terms, and subscription terms when applicable;
- open-source licenses;
- relay-service status/support links when available; and
- a short statement of the phone-held trust mode and what metadata the relay
  and push provider can see.

## Confirmations and authentication surfaces

**Surface:** Transient in-app confirmations, small single-field editors, and
Android-owned authentication surfaces; none are navigation destinations.

These are dialogs or system authentication surfaces, not independent
navigation destinations:

- allow or deny a profile access request when a confirmation is useful;
- accept or reject a Profile proposal;
- delete a Variable or profile;
- suspend, resume, reauthorize, or revoke a client;
- pause or resume new pairings;
- clear completed Request history;
- change pairing address or discard an incomplete claim;
- reveal/copy a sensitive value; and
- create, widen, extend, enable, or revoke an approval policy.

Confirmation copy names the affected object and consequence. Destructive
buttons use the precise verb—**Revoke**, **Delete**, **Clear**, **Deny**—rather
than a generic **OK**.

Authentication is requested only after the user starts the operation.
Cancellation leaves persisted state unchanged.

Factory reset is deliberately excluded from this list because it uses the
dedicated full-screen flow defined above.

Simple Rename actions use a small labeled name editor rather than gaining a
screen in the navigation tree. Changing the pairing address reuses the Claim
pairing address editor in replacement mode, adding the existing-Client
consequences and replacement progress. Google Play owns the purchase system
surface launched from Plan and billing.

## Cross-screen behavior and presentation

### Timestamps and outcomes

- Format times in the device locale and time zone.
- Lists may use relative time plus date grouping; details show full date and
  time.
- Never derive “delivered” from the phone's approval alone. Show it only after
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
- Profile names, Variable names, commands, paths, Reasons, hostnames, and
  usage patterns are sensitive metadata even though they are not secret values.
- Push wake signals and public lock-screen notification content remain generic.
  Agentknock marks Request details as private; Android's user-controlled
  visibility settings determine whether the minimum metadata needed for an
  informed one-time action appears on the lock screen.
- Screens that show command or Reason data should opt out of unintended
  system capture where practical, without claiming this defeats a compromised
  phone.
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

1. **Request history limit.** Non-terminal Requests are never pruned, while
   terminal Requests are bounded independently of the one-year Audit log.
   Decide the exact terminal-record limit; 100 is the current candidate.
2. **Client friendly name timing.** Decide whether the default reported
   hostname is accepted automatically after correct SAS or whether naming is a
   required pairing step. It remains editable either way.
3. **Client reauthorization contract.** Define the verification evidence,
   protocol lifecycle, expiry, and effect on existing policies before the
   focused reauthorization screen is finalized. Reported machine metadata is
   not sufficient evidence by itself.
4. **Profile type contracts.** Define the exact public metadata, fixed provided
   environment-variable names where applicable, request result, delivery
   behavior, and combination rules for each type before its screens are
   finalized.
5. **AWS credential generation.** Choose the authorization mechanism,
   configuration fields, storage of any long-lived material, duration limits,
   error model, and revocation behavior for AWS temporary credentials.
6. **SSH delivery.** Define the client-side helper, authorization boundary,
   operation lifecycle, and whether the first version supports signing only or
   any other key operation.
7. **Sensitivity granularity.** A Variable can have a sensitivity choice,
   while inherently confidential profile types require protected handling.
   Decide whether compound type metadata ever needs field-level sensitivity.
8. **High-value scope.** Decide whether step-up authentication is attached to
   an entire profile, individual Variables within an Environment variables
   profile, or both. Profile-level classification works consistently across
   all profile types.
9. **Paid capabilities.** No feature-to-plan mapping or price is assumed here.
   Decide what remains free, what is paid, usage limits, and whether manual
   phone-held release always remains available during billing problems.
10. **Subscription identity.** Decide how a Google Play purchase is bound to the
   pseudonymous Agentknock device identity, and what restoration or
   multiple-device behavior is promised before the backend entitlement model
   is designed.
11. **Remote reviewer trust mode.** Provider, visible fields, evaluation gates,
   fallback behavior, and subscription economics remain later decisions.

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
