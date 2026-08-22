# Agentknock terminology

Agentknock uses simple language at the product surface and more precise terms
where the distinction matters. A **secret** is the deliberately broad public
name for a protected unit that a client can request for an operation; this does
not mean that every value inside it is confidential.

Product concepts are common nouns and remain lowercase in prose. Screen
titles, navigation destinations, field labels, and actions use sentence case,
such as **Secrets**, **Secret use**, and **Client ID**. Agentknock, Android,
AWS, SSH, CLI, and user-assigned names retain their normal capitalization.
Exact type, mode, decision, and status labels retain their displayed
sentence-case capitalization when quoted in prose.

| Term | Meaning and usage |
| --- | --- |
| **Agentknock** | The product as a whole. |
| **Relay** | The Agentknock network service through which clients and devices exchange protocol messages. It temporarily stores and forwards messages so they do not need to be online simultaneously. It does not receive decrypted secret contents. |
| **Relay URL** | The network address used to reach a relay. Show it only where technically useful, such as connection diagnostics. It is distinct from the pairing address. |
| **Device** | This Android app installation. It owns the device identity, device-held keys, secrets, approval rules, and approval decisions. It is not called a vault. |
| **Device ID** | The opaque technical identifier for this device. It belongs in technical details and is not the pairing address. |
| **Client** | One Agentknock CLI installation that is paired with, or attempting to pair with, this device. It may run on a laptop, server, VM, container, or hosted runner. |
| **Client name** | The user-assigned display name for a client, such as **Build workstation**. It is distinct from the hostname reported by the client. |
| **Client ID** | The opaque technical identifier for a client. It does not replace the client name in ordinary UI. |
| **Hostname** | The machine hostname reported by a client. Treat it as supporting, client-supplied information rather than the client's authoritative name. |
| **Pairing address** | The public three-word address used only to begin pairing with a device. It is not an identifier for an existing client and does not grant access. |
| **Pairing** | The process of establishing, and the resulting encrypted relationship between, one client and one device. |
| **Vault** | The protected storage inside a device. Use this term when discussing storage, encryption, backup, or recovery—not as another name for the device or for a secret. |
| **Secret** | A named unit with one concrete type that a client requests as a whole. Secret names share one flat namespace on the device. Examples include `github-release`, `aws-read-only`, and `production-ssh`. This is the primary user-facing replacement for *profile*. |
| **Secret type** | The immutable behavior and delivery contract of a secret. Initial types are **Environment variables**, **AWS temporary credentials**, and **SSH key**. In ordinary UI, a simple **Type** label is sufficient. |
| **Environment variables** | A secret type containing a cohesive set of named environment variables that are provided together. |
| **Environment variable** | One environment variable, including its name and value, provided to a command through its process environment. It may belong to a secret of type **Environment variables** or be provided by another secret type. Never shorten this term to *variable*. |
| **Sensitive** | A handling classification applied to a stored value. A secret of type **Environment variables** may contain both sensitive and non-sensitive environment variables. |
| **Credential** | Specific authentication material, such as an API token, AWS access-key set, or SSH private key. Do not use it as the umbrella name for every secret. |
| **AWS temporary credentials** | A secret type that provides a short-lived AWS credential set with a shared lifetime, plus any supported companion environment variables such as `AWS_REGION`. |
| **SSH key** | A secret type that owns one SSH key and provides SSH-specific use without exposing it as an environment variable. |
| **Request** | A durable authorization workflow resolved by protocol validation, a device-held approval rule, or a user decision. It remains in bounded request history after completion. |
| **Reason** | An optional explanation supplied by the client for a request. It is untrusted supporting information, not an authoritative description of the operation. |
| **Secret use** | A request for a client to use one or more named secrets for one operation. Depending on the secret type, use may provide values or mediate an operation; it does not necessarily disclose protected key material. |
| **Secret upload** | Secret data uploaded by a client for review. The upload remains inactive until approved on the device; rejecting it discards the uploaded data. **Create** makes a new secret. **Update** changes supplied content and retains unspecified content. **Replace** supplies the complete content and removes unspecified content. |
| **Approval rule** | A device-held rule with one action for a matching secret use request: **Approve**, **Ask me**, **Deny**, or **Ask AI**. A standing rule is created directly by the user and has no expiry; a temporary rule is created while approving a request and expires. Rules currently apply only to secret use; pairing and secret uploads require a user decision. Use **Rules** as the primary-navigation label and **Approval rules** as the screen title or fuller name. **Ask AI** is provisional wording to revisit before release. |
| **Automatic approvals** | The optional feature used by an **Ask AI** rule. An AI reviewer evaluates the matching request and may approve it, deny it, or ask the user. Do not use this term for deterministic **Approve** rules. |
| **Audit event** | One append-only record of a security-relevant action or state transition, including operations that did not require approval. Audit events form the **Audit log**. |

| Interaction | Request label | Notification |
| --- | --- | --- |
| Pairing | **Pairing** | **Pairing requested** |
| Secret use | **Secret use** | **Secret use requested** |
| Secret upload | **Secret upload** | **Secret upload received** |

| Other context | Preferred wording |
| --- | --- |
| Primary navigation | **Requests**, **Secrets**, **Clients**, and **Rules** |
| Creation | **New secret** |
| Pairing decision | **Accept** / **Reject** |
| Secret use decision | **Approve** / **Deny** |
| Upload change mode | **Create**, **Update**, or **Replace** |
| Upload decision | **Approve** / **Reject** |
| Upload outcome | **Upload approved** / **Upload rejected** |
| Requested-object section | **Requested secrets** |
| CLI selector | `--secret` / `-s` |

Avoid **profile** as the general public term: it does not communicate what is
being protected. Use it only where another system gives it a specific meaning,
such as an AWS profile.
