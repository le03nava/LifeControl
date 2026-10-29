# ODD feature: validate-env-secrets-directory

**Repository**: LifeControl — `docker/scripts/validate-env.sh` and one new suite in
`docker/scripts/tests/`. No `life-control-api/` change, no Angular change, no compose file, no
migration, no CI workflow change.
**Status**: implemented — `validate-env.sh` reports a directory inside `docker/secrets/` as what it is
instead of as a secret with the wrong permissions, and the suite pins both shapes plus the gate's
existing failures. Nothing is deferred.
**Created**: 2026-10-29 · **Branch**: `fix/validate-env-secrets-directory` ·
**Risk**: medium — `docker/scripts/validate-env.sh` is the gate `deploy.sh prod start` runs before it
brings production up, so a wrong relaxation here would let a missing production secret through, and a
wrong strictness would stop a start. No stored data, no contract, no auth, no shared runtime code.

## Specs

**S1** — *the request, verbatim* (L1): «continua con el arreglo», sobre lo ofrecido en L2: que un
**directorio** en `docker/secrets/` dé un warning que se explica solo en vez de un `ERROR` de modo.

**S2** — *the measured defect, as observed on this host* (L3). `./docker/scripts/validate-env.sh dev`
printed, in this order, for `docker/secrets/smtp_password`:

```
grep: /…/docker/secrets/smtp_password: Is a directory
[SUCCESS] /…/docker/secrets/smtp_password set
[ERROR] /…/docker/secrets/smtp_password mode is 755 — must be 0444 so container uids (999/1000/472) can read it
[ERROR] Validation failed with 1 error(s)
```

The directory is Docker's own artifact, not an operator's mistake: compose bind-mounts
`./secrets/smtp_password` and Docker creates an empty directory in place of a bind source that does not
exist. `docker-compose.yml:354-357` documents it and the compose file states the entrypoint treats it as
"no credential". So the gate is failing on a state the repository itself produces.

**S3** — *what must not change, because it is why the gate exists*: every secret that `setup-env.sh`
materializes from a `.template` — the five files that exist as `*.template` — keeps failing as an
`ERROR` when it is a directory instead of a file, exactly as it fails when it is missing, empty, still
carries `CHANGEME`, or is not mode `0444`. `deploy.sh prod start` aborts on any of those.

**S4** — *also sanctioned, and measured in the same run*: an entry the gate cannot read is never
reported as a success. The `[SUCCESS] … set` line above was emitted for a directory, from `grep -q`
returning non-zero for "Is a directory" rather than for "no CHANGEME".

**S5** — *the count that decides whether anything was materialized*: a directory does not count as a
materialized secret. A `docker/secrets/` holding only that directory still reports
`No secrets materialized`, because the alternative is telling an operator their secrets are in place when
none are.

**S6** — *the requirement that is conditional, added by the review's one finding (L8)*: `smtp_password`
is not unconditionally optional. `SMTP_AUTH=true` makes it required, and two programs refuse without it —
`keycloak-setup.sh:262-278` refuses to write the relay when the file is missing, empty or still holds
`CHANGEME`, and Grafana's entrypoint exports it only when `GF_SMTP_USER` is non-empty. So with
`SMTP_AUTH=true`, a directory in its place **and** no file at all are both `ERROR`s, and the gate never
prints a claim that nothing is missing while the relay is configured to authenticate.

## Tasks

| ID | Specs | What | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | — | This record | inline (parent) | this commit |
| T2 | S2–S5 | RED: `docker/scripts/tests/secrets-directory.test.sh` drives the real gate inside a scratch tree and pins the two directory shapes, the four existing failures, the success control and the `No secrets materialized` case | inline (parent) | this commit |
| T3 | S1–S5 | GREEN: `check_secret_files` branches on a directory before it reads content or mode, and picks warning or error by whether a `.template` declares the secret | inline (parent) | this commit |
| T4 | S1–S5 | Evidence: every suite in `docker/scripts/tests/` plus `shellcheck` | inline (parent) | this commit |
| T5 | S6 | Correction from the review's finding R3-001: `check_smtp_credential` requires the hand-created credential whenever `SMTP_AUTH=true`, for a directory and for an absent file alike, and the directory warning stops claiming that nothing is missing | inline (parent) | this commit |

## Log

**L1** — the request, verbatim: «continua con el arreglo».

**L2** — the offer L1 accepts, verbatim: «que un **directorio** en `docker/secrets/` dé un warning que se
explica en vez de un ERROR de modo (hoy borré el directorio, pero el próximo `docker compose up` que
recree Grafana lo vuelve a crear y el ERROR vuelve). Eso es `docker/scripts/validate-env.sh` + un caso en
una suite de `docker/scripts/tests/`, en rama con `new-unit.sh` y su PR.»

**L3** — evidence, the pre-fix run: `cd docker && ./scripts/validate-env.sh dev` on 2026-10-29, before
the empty directory was removed. Full relevant excerpt recorded under S2; the same run also printed
`[SUCCESS] … pins the same image versions as /…/.env.template (11 compared)` and four
`PORT is already in use` warnings from the running dev stack, which are warnings by design.

**L4** — decision, the discriminator: the branch is chosen by **`<name>.template` existing**, not by the
secret's name. The template set is exactly what `setup-env.sh` materializes, so a missing member of it is
a real misconfiguration, and a secret with no template — today only `smtp_password`, which
`docker-compose.yml:354` says is "required only when the relay authenticates" — is by construction a
hand-created file whose absence is legal.

**L5** — decision, the wording: the warning names the path, says Docker created the directory in place of
a missing bind source, says the secret is only read when the relay authenticates, and gives the
`rmdir` that removes it. The error for a templated secret names the same path and the `setup-env.sh`
command that materializes it, so the two cannot be confused for each other.

**L6** — evidence, 2026-10-29, all in this unit's worktree:

- `./docker/scripts/tests/secrets-directory.test.sh` before the change: `15 passed, 9 failed`, the first
  failure reading `expected 0, got 1` — the gate refused the environment over the optional directory, and
  the run leaked `grep: …: Is a directory` beside its own `[SUCCESS] … set`. After the change:
  `24 passed, 0 failed`.
- Every suite in `docker/scripts/tests/`, by glob: **21 suites, 0 failing** — `secrets-directory` 24,
  `validate-env-strict-pins` 25, `env-defaults` 264, `env-image-pin-drift` 44, `exporters` 71,
  `image-revision` 71, `keycloak-setup` 68, `image-pins` 57, `observability-port-binding` 41,
  `grafana-alerting` 68, `grafana-provisioning` 31, `prometheus-parity` 23, `grafana-datasources-parity`
  18, `freshness-guard` 18, `loki-retention` 14, `observability-probes` 12, `version-consistency` 8,
  `compose-profile` 6, `worker-dev-default` 6, `tempo-receivers` 5.
- `docker run --rm -v "$PWD:/mnt" -w /mnt koalaman/shellcheck:stable --severity=warning
  docker/scripts/validate-env.sh docker/scripts/tests/secrets-directory.test.sh` → exit 0. That is the
  same image and severity the `Shell & Artifact Integrity` job uses, and its glob
  (`find docker/scripts -type f -name '*.sh'`, `.github/workflows/docker-build-integrity.yml:35`) covers
  both files, so a lint regression in either one fails CI.
- `./.github/scripts/tests/odd-status-headers.test.sh` → `78 passed, 0 failed`, `checked 125 record(s)`,
  so this record's header satisfies the closed verdict vocabulary.

**L7** — gap, recorded rather than assumed: `shellcheck` is not installed on this host, so the lint above
ran through the pinned container image. `bash -n` was run on both files as a second, daemon-free check.

**L8** — review finding, `R3-001` (`review-reliability`, `CRITICAL`, `causal_disposition: introduced`),
verbatim: «The no-template directory branch treats every directory without a matching .template as
optional: it prints a warning, does not increment ERRORS, and continues. The task record acknowledges
smtp_password is required when the relay authenticates; in that configuration a Docker-created empty
directory is a missing required secret, but this branch lets validate-env.sh exit successfully and even
says "no secret is missing". The new test suite only exercises the default/no-auth shape, so this
regression is not caught.» The finding is correct and the causality is right: before this unit a
directory there failed the gate — for the wrong reason (mode `755`), but it failed — and the first version
of the fix turned that accident into a pass.

**L9** — decision, the correction (S6): the requirement lives in its own check rather than in the
directory branch, because the same rule has to cover the absent file, which the loop's glob never sees.
`check_smtp_credential` mirrors `keycloak-setup.sh:262-278` — non-empty regular file, no `CHANGEME` — and
does nothing at all unless `SMTP_AUTH=true`, so no environment that does not relay authenticated mail
changes behavior. The directory warning also stops asserting that nothing is missing, because that
sentence was the finding's evidence.

**L10** — evidence, 2026-10-29, the correction: `secrets-directory.test.sh` before it `28 passed, 4
failed` (the two `SMTP_AUTH=true` cases and the two text assertions), after it `32 passed, 0 failed`;
`shellcheck` exit 0 on both files through the pinned image; every suite in `docker/scripts/tests/` green
again after the change.
