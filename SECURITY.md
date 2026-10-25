# Security Policy

## Reporting a vulnerability

Please report security issues privately. Do **not** open a public issue
for a vulnerability.

- Preferred: GitHub's private vulnerability reporting,
  **Security → Report a vulnerability** on this repository
  (GitHub Security Advisories).
- Or contact Ghost Developer Studio through the studio profile linked
  from this repository.

We aim to acknowledge a report within a few days and will agree a
disclosure timeline with you.

## Scope: what Ghost Hands Mobile is, and is not

Ghost Hands Mobile is an on-device agent: the model, the governor, and
the trail all live on the phone. Be clear-eyed about the boundaries when
you assess it:

- The Governor gates **the actions the app executes**: classification,
  policy packs, domain rules, site grants, approvals. It cannot govern
  what another app, a website, or a human does outside it.
- Policy is deterministic pattern matching over actions and targets. It
  raises the cost of accidents and common prompt-injection shapes; it is
  not a sandbox and not a proof. The model itself is never trusted: its
  output is parsed fail-closed and judged before anything runs.
- Model files are downloaded from the official Qwen Hugging Face repo
  and pinned by byte size and SHA-256 in `ModelStore`. A report that a
  pin can be bypassed is squarely in scope.
- The trail is the audit record. It lives in the app's private storage.
  Treat exported trails and screenshots as personal data: they can
  contain the pages you visited and the answers you were given.
- The v3.14 accessibility service is a fenced spike: system-bound, not
  exported, inert unless the user enables it in Android Settings AND
  flips the in-app experimental toggle. Password text is never copied
  into a sketch. Any way to make it act without both gates is in scope.
- Downloads are governed like actions (v3.16): executable and archive
  shapes are consequential and ask first. A download path that slips
  that classification is in scope.

Vulnerabilities in the Governor's classification (an action classed
below its real consequence), the record-before-execute guarantee, the
model pin, the download gate, or the accessibility fence are the reports
we most want.

## Supported versions

Only the latest shipped release receives fixes. Older APKs are
superseded builds, not maintained branches.
