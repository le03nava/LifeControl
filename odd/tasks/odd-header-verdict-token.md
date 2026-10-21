# ODD feature: odd-header-verdict-token

**Repository**: LifeControl — the guard that enforces the ODD record contract
(`.github/scripts/tests/odd-status-headers.test.sh`), the contract it enforces
(`.agents/skills/project-conventions/references/feature-records.md`, `AGENTS.md`) and the 23 record
headers whose opening word is not a verdict. **No production code, no workflow, no dependency, no
`.env*` file, and no record's narrative beyond its opening token.**
**Status**: implemented — the closed vocabulary, the guard rule, the parity assertion and the
normalisation of the 23 headers on the tree are written and measured, and this record travels in the
pull request that carries them. Nothing is deferred.
**Created**: 2026-10-21 · **Base**: `main` @ `df88aae` · **Branch**: `chore/odd-header-verdict-token` ·
**Worktree**: `~/workspace/LifeControl-worktrees/chore-odd-header-verdict-token` (herdr workspace `w59`)
**Risk**: **item 3 of the task-size list** — the change moves a contract every future record is written
against, and it *tightens* a CI gate that runs on every pull request, so a wrong rule blocks all of them.
It is argued down rather than waved through: the vocabulary is closed at five tokens, every one of the
84 records on the tree is audited by the suite's first assertion (which fails before any fixture runs),
the 23 non-conforming headers are normalised in the same pull request, and the vocabulary the guard
accepts is asserted equal to the one the contract documents, so the two cannot drift in either
direction.

## Objective

Close the half of the guard that a denylist cannot close: **an opening word the guard does not
recognise must be a finding, not a pass.** Today the audit answers *no forbidden phrase found* and *a
decline phrased in words I do not carry* with the same `PASS`, so a header written in Spanish, in a
unit id, or in any phrasing nobody imagined reads exactly like a green one — the asymmetry the file
already refuses for its ancestry half, which says `ancestry: skipped` out loud because *a silent skip is
indistinguishable from a pass*.

## The measurement this change answers

| Fact | Value | Command |
| --- | --- | --- |
| Records on the tree | **84** | `ls odd/tasks/*.md \| wc -l` |
| Opening with `merged` | **59** | the sweep below |
| Opening with `implemented` / `superseded` | **1** / **1** | same |
| Opening with something else | **23** (`delivered` ×11, `closed` ×4, `done`, `landed`, `w1` ×2, `w1b`, `w0`, `s1`, `all` ×2, `the`) | same |
| Phrases the denylist carries | **15** (8 English, 5 Spanish plus the two mirrors) | `live_claim_phrases` |
| Distinct phrasings a denylist would have to imagine to close the same hole | unbounded — the reason this change does not extend the list | — |

The sweep, run on `main` @ `df88aae`, matching the first word of each `**Status**:` paragraph against
the five tokens:

```bash
awk 'FNR==1{f=FILENAME; tok=""}
     tok=="" && match($0,/\*\*Status\*\*:/){s=substr($0,RSTART+11); gsub(/\*/,"",s); sub(/^[ \t]+/,"",s);
       if (match(s,/^[A-Za-z0-9][A-Za-z0-9_-]*/)) tok=tolower(substr(s,RSTART,RLENGTH))}
     ENDFILE{c=(tok~/^(merged|implemented|deferred|blocked|superseded)$/); n++; if(!c) print f" :: "tok}
     END{print "total "n}' odd/tasks/*.md
```

The earlier estimate of *10* was measured against a laxer trial vocabulary that accepted `delivered`,
`closed`, `landed` and `done` as verdicts; the vocabulary this record fixes at is the five tokens
`AGENTS.md` already names as the durable outcomes, so the 23 are counted against it.

## Specs

- **S1 — The closed vocabulary, layer 2.** The user's decision, verbatim: *«Sí, capa 2 ahora»*, choosing
  *«Issue + guard + fixtures: ~120 líneas. Cierra el agujero de raíz, en cualquier idioma.»* The
  `**Status**:` paragraph must open with exactly one of `merged`, `implemented`, `deferred`, `blocked`,
  `superseded`.
- **S2 — No creation-date cutoff; the 23 are normalised.** Verbatim: *«Sin cutoff: se normalizan los
  10»*. No `**Created**:` exemption exists in the guard, and every record on the tree opens with a
  canonical token after this change.
- **S3 — An unrecognised opening word is a FINDING, in any language.** The finding is produced by the
  audit and makes it exit non-zero; it is never reported as a pass. This is the defect the change
  exists for: `delivered`, `entregado`, `w1b merged`, `all five slices are merged` and a paragraph that
  opens with no readable token at all are findings, exactly like `awaiting review` is.
- **S4 — The finding is actionable.** It names the offending opening word (or says there is none) and
  names the five accepted tokens, so a writer can fix the header without reading the guard.
- **S5 — No false positive.** A canonical token is legal in any case and behind Markdown emphasis
  (`MERGED — …`, `**merged — …`), and a word that merely *starts* with a canonical token
  (`merged-upstream`) is not one.
- **S6 — Code and contract cannot drift.** The vocabulary the guard accepts is asserted equal to the
  vocabulary `references/feature-records.md` documents, in both directions, and the existing parity
  between the forbidden-phrase row in `AGENTS.md` and the denylist keeps holding.
- **S7 — Everything else about the contract is unchanged.** A live delivery claim still fails
  unconditionally, a transport disclaimer beside delivery evidence still fails, a named merge commit
  still has to be an ancestor of `origin/main`, and a record with no `**Status**:` paragraph still
  fails. The new rule is additive: no existing fixture flips its expected verdict.

## Tasks

| ID | What | Specs | Route | Commit |
| --- | --- | --- | --- | --- |
| T1 | Document the closed vocabulary and normalise the 23 non-conforming headers' opening words | S1, S2 | inline | `f1c0a2d` |
| T2 | Fixtures that assert a finding for an unrecognised token, in English, Spanish, a unit id and a bare word (RED) | S3, S4 | inline | `0b7e31a` |
| T3 | The rule in `audit()`, the vocabulary helper and the doc-parity assertion | S3, S4, S5, S6 | inline | `0b7e31a` |
| T4 | `AGENTS.md`: the token row and the measured suite counts | S1, S6 | inline | `0b7e31a` |
| T5 | Every suite under `.github/scripts/tests/` plus shellcheck, and the differential check against the base commit | S5, S7 | inline | `0b7e31a` |
| T6 | `PR Label Guard` green on this pull request, and the counts re-measured from the run | S6 | — | — |

## Log

- **L1** — *«que esta pendiente»*. The answer, before any write: nothing in flight (`main` clean at
  `df88aae`, zero open pull requests, zero open issues), and one authorisation outstanding — the second
  layer of the ODD header-guard defect, whose layers 1 (doctrine) and 3 (Spanish denylist) had already
  landed in `bd2b5ca` and `8dc7f84`/`7a31c60`.
- **L2** — The two decisions the previous session had left open, answered in one batch. Verbatim:
  *«Sí, capa 2 ahora»* and *«Sin cutoff: se normalizan los 10»*. The recorded option descriptions were
  *«Issue + guard + fixtures: ~120 líneas. Cierra el agujero de raíz, en cualquier idioma.»* and
  *«Se reescribe el token de apertura de los 10 headers viejos y la regla queda sin excepciones.»*
- **L3** — Measurement correcting the brief: the "10" of L2 was counted against a laxer trial
  vocabulary (`delivered`, `closed`, `landed`, `done` counted as verdicts). Against the five tokens
  `AGENTS.md` names, the tree has **23** non-conforming headers, and the normalisation in T1 therefore
  covers 23, not 10. Same decision, larger count; recorded here because the number in the authorising
  answer was wrong.
