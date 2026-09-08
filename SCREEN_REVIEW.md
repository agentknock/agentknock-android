# Screen review queue

One checkbox is one specific Compose preview PNG. Completed checks keep their
original order. Remaining checks prioritize screens not yet reviewed, then
variants most likely to benefit from updates, then the other variants.

All screen reviews use one target: **dark theme, a 360 × 800 dp portrait phone,
and normal text size (100%, font scale 1.0)**. Use this same configuration for
the whole queue and subsequent screen reviews.

For each unchecked item, render the specified preview and variant, ask an AI to
review that PNG, make warranted production Compose changes, and render the same
image again.
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
7. [x] **SSH secret detail** — `secret-ssh / dark`.
8. [x] **Secret access settings** — `secret-access / dark`.
9. [x] **Clients list** — `clients / dark`.
10. [x] **Client detail** — `client-detail / dark`.
11. [x] **Pairing verification** — `pairing / dark`.
12. [x] **Temporary-access confirmation dialog** — `temporary-access / dark`.
13. [x] **Environment upload review** — `secret-upload / dark`.
14. [x] **SSH key upload review** — `ssh-upload / dark`.
15. [x] **Create environment secret** — `create-secret / dark`.
16. [x] **Create environment variable** — `create-variable / dark`.
17. [x] **Edit environment variable** — `environment-variable / dark`.
18. [x] **Generate SSH key** — `create-ssh-key / dark`.
19. [x] **Import SSH key** — `import-ssh-key / dark`.
20. [x] **Edit secret** — `edit-secret / dark`.
21. [x] **Replace SSH key** — `replace-ssh-key / dark`.
22. [x] **Secret AI instructions editor** — `instructions / dark`.
23. [x] **Secret detail: temporary access** — `secret-temporary-access / dark`.
24. [x] **Settings overview** — `settings / dark`.
25. [x] **Security & backup** — `security / dark`.
26. [x] **Welcome** — `welcome / dark`.
27. [x] **Device setup** — `setup / dark`.
28. [x] **Pairing address editor** — `pairing-address / dark`.
29. [x] **App locked** — `locked / dark`.
30. [x] **Notification settings** — `notifications / dark`.
31. [x] **Plan & billing: free plan** — `subscription / dark`.
32. [x] **Audit log** — `audit / dark`.
33. [x] **Audit event detail** — `audit-detail / dark`.
34. [x] **Delete secret confirmation dialog** — `delete-secret / dark`.
35. [x] **Discard changes confirmation dialog** — `discard-changes / dark`.
36. [x] **Security: restored keys** — `security-restored / dark`.
37. [x] **Secret detail: unavailable environment values** — `secret-unavailable / dark`.
38. [x] **Secret use: AI review explanation** — `invocation-ai-review / dark`.
39. [x] **Secret use: AI review in progress** — `invocation-reviewing / dark`.
40. [x] **Secret use: verification failure** — `invocation-failed / dark`.
41. [x] **Requests list: offline** — `requests-offline / dark`.
42. [x] **Unlock failure** — `unlock-failed / dark`.
43. [x] **Pairing failure** — `pairing-failed / dark`.
44. [x] **Pairing address unavailable** — `pairing-address-unavailable / dark`.
45. [x] **Notifications blocked** — `notifications-blocked / dark`.
46. [x] **Suspended client** — `client-suspended / dark`.
47. [x] **About** — `about / dark`.
48. [x] **About: device details** — `about-device / dark`.
49. [x] **Factory reset explanation** — `factory-reset / dark`.
50. [x] **Factory reset action** — `factory-reset-action / dark`.
51. [x] **Factory reset confirmation dialog** — `factory-reset-confirmation / dark`.
52. [x] **Plan & billing: payment pending** — `subscription-pending / dark`.
53. [x] **Plan & billing: store unavailable** — `subscription-unavailable / dark`.
54. [x] **Security: backup section** — `security-backup / dark`.
55. [x] **Secrets list: empty** — `secrets-empty / dark`.
56. [x] **Git signing: signed** — `git-sign-signed / dark`.
57. [x] **SSH authentication: signed** — `ssh-authentication-signed / dark`.

## Remaining variants

58. [ ] **Plan & billing: active subscription** — `subscription-active / dark`.
59. [ ] **Clients list: empty** — `clients-empty / dark`.
60. [ ] **Requests list: empty** — `requests-empty / dark`.
61. [ ] **Audit log: empty** — `audit-empty / dark`.
62. [ ] **Git signing: tag** — `git-sign-tag / dark`.
63. [ ] **SSH authentication: plain public key** — `ssh-authentication-publickey / dark`.
64. [ ] **Secret use: completed** — `invocation-completed / dark`.
65. [ ] **Client detail: technical details** — `client-technical-details / dark`.
66. [ ] **Requests list: long command** — `requests-long-command / dark`.
67. [ ] **Secret use: long command** — `invocation-long-command / dark`.
68. [ ] **Requests list: multiple secrets** — `requests-multiple-secrets / dark`.
69. [ ] **Secret use: multiple secrets** — `invocation-multiple-secrets / dark`.
70. [ ] **Requests list: long names** — `requests-long-names / dark`.
71. [ ] **Secret use: long names** — `invocation-long-names / dark`.
