# Screen review queue

One checkbox is one specific Compose preview PNG. Ordered by importance to approval
decisions and everyday secret/client management, followed by setup, settings,
and less frequent screens.

All screen reviews use one target: **dark theme, a 360 × 800 dp portrait phone,
and normal text size (100%, font scale 1.0)**. Use this same configuration for
the whole queue and subsequent screen reviews.

For each unchecked item, render the specified preview and variant, ask an AI to
review that PNG, edit the production Compose code, and render the same image again.
Check it off when the visual changes are accepted. Checked entries record completed
reviews; other images have their own review status.

Each entry gives `preview / variant`, selecting exactly one image. Select screen
names from this queue with `--variant dark` to stay within the current pass:

```sh
./preview-ui secret-detail --variant dark
```

The [preview guide](app/src/screenshotTest/README.md) explains where to find the PNG.
All entries below already have Compose previews matching the current configuration.

1. [x] **Requests list** — `requests / dark` — accepted in `b6263b6`.
2. [x] **Secret use request** — `invocation / dark` — accepted in `b6263b6`.
3. [x] **Git signing request** — `git-sign / dark` — accepted in `68f8005`.
4. [x] **SSH authentication request** — `ssh-authentication / dark` — accepted in `d49c450`.
5. [x] **Secrets list** — `secrets / dark` — accepted in `befc72b`.
6. [x] **Environment secret detail** — `secret-detail / dark`.
7. [ ] **SSH secret detail** — `secret-ssh / dark`.
8. [ ] **Secret access settings** — `secret-access / dark`.
9. [ ] **Clients list** — `clients / dark`.
10. [ ] **Client detail** — `client-detail / dark`.
11. [ ] **Pairing verification** — `pairing / dark`.
12. [ ] **Temporary-access confirmation dialog** — `temporary-access / dark`.
13. [ ] **Environment upload review** — `secret-upload / dark`.
14. [ ] **SSH key upload review** — `ssh-upload / dark`.
15. [ ] **Create environment secret** — `create-secret / dark`.
16. [ ] **Create environment variable** — `create-variable / dark`.
17. [ ] **Edit environment variable** — `environment-variable / dark`.
18. [ ] **Generate SSH key** — `create-ssh-key / dark`.
19. [ ] **Import SSH key** — `import-ssh-key / dark`.
20. [ ] **Edit secret** — `edit-secret / dark`.
21. [ ] **Replace SSH key** — `replace-ssh-key / dark`.
22. [ ] **Secret AI instructions editor** — `instructions / dark`.
23. [ ] **Secret detail: temporary access** — `secret-temporary-access / dark`.
24. [ ] **Settings overview** — `settings / dark`.
25. [ ] **Security & backup** — `security / dark`.
26. [ ] **Welcome** — `welcome / dark`.
27. [ ] **Device setup** — `setup / dark`.
28. [ ] **Pairing address editor** — `pairing-address / dark`.
29. [ ] **App locked** — `locked / dark`.
30. [ ] **Notification settings** — `notifications / dark`.
31. [ ] **Plan & billing: free plan** — `subscription / dark`.
32. [ ] **Audit log** — `audit / dark`.
33. [ ] **Audit event detail** — `audit-detail / dark`.
34. [ ] **Delete secret confirmation dialog** — `delete-secret / dark`.
35. [ ] **Discard changes confirmation dialog** — `discard-changes / dark`.
36. [ ] **Security: restored keys** — `security-restored / dark`.
37. [ ] **Secret detail: unavailable environment values** — `secret-unavailable / dark`.
38. [ ] **Secret use: AI review explanation** — `invocation-ai-review / dark`.
39. [ ] **Secret use: AI review in progress** — `invocation-reviewing / dark`.
40. [ ] **Secret use: verification failure** — `invocation-failed / dark`.
41. [ ] **Requests list: offline** — `requests-offline / dark`.
42. [ ] **Unlock failure** — `unlock-failed / dark`.
43. [ ] **Pairing failure** — `pairing-failed / dark`.
44. [ ] **Pairing address unavailable** — `pairing-address-unavailable / dark`.
45. [ ] **Notifications blocked** — `notifications-blocked / dark`.
46. [ ] **Suspended client** — `client-suspended / dark`.
47. [ ] **Plan & billing: active subscription** — `subscription-active / dark`.
48. [ ] **Plan & billing: payment pending** — `subscription-pending / dark`.
49. [ ] **Plan & billing: store unavailable** — `subscription-unavailable / dark`.
50. [ ] **Secrets list: empty** — `secrets-empty / dark`.
51. [ ] **Clients list: empty** — `clients-empty / dark`.
52. [ ] **Requests list: empty** — `requests-empty / dark`.
53. [ ] **Audit log: empty** — `audit-empty / dark`.
54. [ ] **Git signing: tag** — `git-sign-tag / dark`.
55. [ ] **SSH authentication: plain public key** — `ssh-authentication-publickey / dark`.
56. [ ] **Secret use: completed** — `invocation-completed / dark`.
57. [ ] **Git signing: signed** — `git-sign-signed / dark`.
58. [ ] **SSH authentication: signed** — `ssh-authentication-signed / dark`.
59. [ ] **Security: backup section** — `security-backup / dark`.
60. [ ] **Client detail: technical details** — `client-technical-details / dark`.
61. [ ] **About** — `about / dark`.
62. [ ] **About: device details** — `about-device / dark`.
63. [ ] **Factory reset explanation** — `factory-reset / dark`.
64. [ ] **Factory reset action** — `factory-reset-action / dark`.
65. [ ] **Factory reset confirmation dialog** — `factory-reset-confirmation / dark`.
66. [ ] **Requests list: long command** — `requests-long-command / dark`.
67. [ ] **Secret use: long command** — `invocation-long-command / dark`.
68. [ ] **Requests list: multiple secrets** — `requests-multiple-secrets / dark`.
69. [ ] **Secret use: multiple secrets** — `invocation-multiple-secrets / dark`.
70. [ ] **Requests list: long names** — `requests-long-names / dark`.
71. [ ] **Secret use: long names** — `invocation-long-names / dark`.
