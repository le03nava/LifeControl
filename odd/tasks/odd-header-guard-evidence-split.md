# ODD feature: odd-header-guard-evidence-split

**Repository**: LifeControl — one file, `.github/scripts/tests/odd-status-headers.test.sh` (its rule,
its vocabulary, its header comment and four fixtures) plus this record. **No production code, no
workflow, no edit to any record's narrative.**
**Status**: merged — pull request **#480**, merge commit `4b7212c`, 2026-10-20. The two holes of issue
**#472** are closed in the suite and checked: the delivery-state class now requires a merge commit the
ancestry half can verify, the bare pending-delivery phrase became vocabulary (the `yet` is gone), and the
real pre-repair header of `access-provisioning-worker-repairs.md` — the case that passed green — fails the
new rule. No work left in this unit.
**Created**: 2026-10-19 · **Branch**: `chore/odd-status-headers-split` · **Base**: `main` @ `d7870b1`
· **Worktree**: `~/workspace/LifeControl-worktrees/chore-odd-status-headers-split` (herdr `w4Z`)
· **Issue**: **#472** · **Risk**: **item 5 (CI)** — this file is a gate: `pr-label-guard` runs every
`.github/scripts/tests/*.test.sh` on every pull request, so a rule that fires on a legitimate header
blocks unrelated work.

## Specs

Quoted verbatim from issue #472's *Acceptance criteria*.

**S1** — "A fixture reproducing the incident — `what remains is the delivery` plus `#460` and no hash
— makes the suite fail, and fails with the new message naming the phrases (`declines its own delivery
with no merge commit`)."

**S2** — "A fixture with a bare `not merged`, a pull-request number and no hash fails, which is the
hole the `yet` closed."

**S3** — "A fixture with `implemented` and a pull-request number and **no** hash still passes: the
zero-blast-radius pin."

**S4** — "A fixture with a delivery-state phrase **plus** a merge commit that is an ancestor of
`origin/main` passes, so the stricter class is satisfiable rather than unsatisfiable."

**S5** — "`bash .github/scripts/tests/odd-status-headers.test.sh` exits 0, asserts every fixture, and
the real-tree assertion reports every record the tree holds — **79** before this unit's record was
added and **80** with it, since the record is audited like any other — with its 115 claimed merge
commits."

**S6** — "The `ci-speed-shape` fixture and the four quotation-carrying records stay green, and so do
`docker-images-rebuild` and `project-conventions-skill`."

**S7** — "The file's own header comment states the split rule and the cost it accepts, so the next
reader does not re-derive it from the code."

## Tasks

| ID | Task | Specs | Route | Commit |
|----|------|-------|-------|--------|
| T1 | Measure the blast radius over the 79 records before designing anything: which carry declining vocabulary, which of those are delivery-state, which have a hash | — | inline | log |
| T2 | The rule: `declines_its_delivery` isolates the delivery-state class, `audit` requires a nonempty `delivery_hashes` for it, and `not merged yet` becomes `not merged` | S1, S2, S4 | delegated (`gentle-ai-worker`) | see log |
| T3 | Four fixtures, test-first, plus the header comment that states the rule and its cost | S1–S4, S7 | delegated | see log |
| T4 | Verification: the tree's verdict before and after, the real incident text against both rules, shellcheck | S5, S6 | inline | log |
| T5 | This record, and the delivery entry once the pull request exists | — | inline | log |

## Log

**L1 — the user's original request, verbatim**: "con que seguimos?"

**L2 — the request it answers, verbatim**: "si dale a 1 y 3" (the header repairs) and then, for this
unit, the accepted option *"El guard: medir el hueco y arreglarlo"* — measure the hole first, and the
design follows the measurement rather than the other way round.

**L3 — the measurement that set the design** (79 records on `main` @ `d7870b1`, the suite's own
`status_paragraph`, `declining_phrases` and `delivery_hashes` sourced from the file itself):

| Class of phrase | Records | With a hash | Without a hash |
| --- | --- | --- | --- |
| Delivery-state (`not merged`, `what remains is the delivery`, `not pushed`, `yet to land`, `awaiting/pending review`, `in progress`, `unstarted`) | **0** | 0 | 0 |
| Soft (`implemented`, `makes no claim about … push`) | 8 | 4 | 4 |

So a class split costs the tree **zero** verdicts today, while a blanket "every declining header needs
a hash" would have failed four honest records. A second measurement found the vocabulary hole: the
list carried `not merged yet`, so a bare `not merged` matched nothing, and no `Status` paragraph on the
tree carries one today — while two carry near-matches that must keep passing,
`docker-images-rebuild` (*"there is no branch and no PR"*, a dev-only unit with nothing to deliver)
and `project-conventions-skill` (*"closed without merging"*, the documented non-ancestor case).

**L4 — the RED/GREEN, observed by the delegated writer and re-read here**: with only the four fixtures
written, the suite reported `FAIL: a declined delivery backed only by another unit's number fails
(expected fail, got exit 0)`, `FAIL: the missing merge commit is named …` and `FAIL: a bare not merged
with only a pull request number fails (expected fail, got exit 0)` — `14 fixture(s) asserted`,
`22 passed, 3 failed`. After the rule: `25 passed, 0 failed`. The two fail-fixtures were green under
the old rule, which is the defect restated as a test.

**L5 — the verification this record rests on, run by the parent rather than accepted on report**:

- The same tree, two rules: the suite at `origin/main` prints `10 fixture(s) asserted` / `20 passed, 0
  failed` and the changed one prints `14 fixture(s) asserted` / `25 passed, 0 failed`, both reporting
  the same real-tree verdict — `checked 79 record(s); 115 claimed merge commit(s); ancestry: ran`, no
  `PROBLEM` line. That measurement predates this record, and adding the record is the only thing that
  moves the count: over the final tree the changed suite reports `80 record(s)` with the same 115
  claims and still no `PROBLEM`, because this unit's own record is audited like any other. The delta
  the two rules produce on any single record is zero, which is what the claim is about.
- The **real** incident text, not a synthesized fixture: `git show 7dbd3aa:odd/tasks/access-provisioning-worker-repairs.md`
  into a scratch record tree, audited by both rules. The base rule printed `READ 1 / DELIVERY 0 /
  ancestry: ran` and exited **0** — the measured hole; the new rule printed
  `PROBLEM: … declines its own delivery with no merge commit in its Status paragraph (phrases: What
  remains is the delivery, not merged)` and exited **1**.
- `docker run --rm … koalaman/shellcheck:stable --severity=warning .github/scripts/tests/odd-status-headers.test.sh`
  — no output, which is the workflow's own gate.
- `git diff -U0 | grep '^-[^-]'` — the only removed lines are the old comment and the old rule; no
  existing fixture lost a line.

**L6 — what was deliberately not done**: proving the ownership of a `#N` is not decidable offline (no
`gh`, no token, no network, by design), so the rule asks the stricter class for evidence it *can*
check — a merge commit and its ancestry — instead of guessing. The stale sentence in
`.agents/skills/project-conventions/references/feature-records.md` that still says *"No automated
guard enforces this"* was found while writing this and is **not** fixed here; it is a separate change.

**L7 — the accepted cost, stated where a reader will meet it**: a genuinely mid-flight unit can no
longer say `awaiting review` and back it with only a pull request number. `references/feature-records.md`
already forbids every phrase of that class in a header, and fixture `delivery-state-with-merge-commit`
pins that the class is satisfiable rather than impossible.

**L9 — the independent verification found one defect, in this record, and two advisories**: measured
by `gentle-ai-verify` over commit `b70db9f`, which confirmed the nine checks it was given (the suite
against the same tree under both rules, the `7dbd3aa` incident failing now and passing before, the
`declines_its_delivery` ⊆ `declining_phrases` invariant extracted mechanically, no fixture loosened,
shellcheck, the workflow wiring, and the L3 measurement recomputed) and then failed this record's own
header: it said *"The delivery is recorded in the log below"* while no delivery row existed. That is
a durable claim with no evidence — the class rule 1 of `references/feature-records.md` forbids — and
this suite **cannot** see it, because its vocabulary has no phrase for a self-referential delivery
claim. The sentence is gone; the delivery entry lands here with the pull request, as `T5` says. Two
advisories were accepted without a correction round, with the reason recorded rather than implied:
(i) another unit's merge commit still closes a delivery-state decline, because hash ownership is as
undecidable offline as number ownership — the file's header comment now says that instead of implying
the opposite, and it is not a regression against `d7870b1`, where a bare number sufficed; (ii) a
`merged — commit \`<hash>\`` without a delivery marker fires, and the failure message now names the
marker requirement (*behind a delivery marker*), so an author is not told they wrote no merge commit
when they wrote one.

**L8 — this unit's own record was the rule's first catch, and the header was corrected instead of the
rule** (2026-10-19). The first draft of the `**Status**:` paragraph above *explained* the change in
prose — "the bare pending-delivery phrase is vocabulary" — and it spelled the phrase out, so the
suite reported `PROBLEM: odd/tasks/odd-header-guard-evidence-split.md: declines its own delivery with
no merge commit in its Status paragraph (phrases: makes no claim about push, not merged)` and the
real-tree assertion went red at `24 passed, 1 failed`. That is the rule working as designed and not a
false positive to be loosened: this suite protects quotations by **scope**, not by escaping — the
records that legitimately quote the forbidden phrases keep them outside the first paragraph — and a
header is a verdict, not a discussion. The paragraph now names the change without asserting the
phrase, and the explanation lives here. Kept because it is the only measured case of the cost the
split accepts, and because a later reader deserves to know the failure mode before writing a header.

**L10 — the native review found the arithmetic, and it was mine** (2026-10-19, finding `R3-1`, one
bounded correction). The reliability reviewer and the refuter agreed on one CRITICAL, candidate-caused
finding: `L5` claimed the changed suite still reported `79 records` over the real tree, while the
candidate adds **this** record to `odd/tasks/` and the suite audits that path, so the final output
reports `80`. The number was measured before the record existed and then published as if it described
the candidate — the defect this unit exists to catch, committed inside its own record. Both states are
stated now, `S5` in issue #472 was corrected to count the record it audits, and the claim about
**verdicts** — no record changes verdict under either rule — stands untouched, because that is what the
measurement actually supports.

**L11 — la entrega, que este registro debía desde que existe** (2026-10-20): la unidad cerró el issue
**#472** como pull request **#480**, merge commit **`4b7212c`**, mergeado el 2026-10-20. Hasta esta
entrada el header decía *"This header makes no claim about push or PR state"*: la frase que el propio
unit dejó escrita porque la entrega todavía no existía, y que dejaba al registro que documenta la regla
del header sin la entrega que esa regla manda registrar. La regla 3 de `references/feature-records.md`
pide la entrega en el log fechado con fecha, PR y commit, y acá no había ninguna fila. El header ahora
declara el estado entregado con ese mismo merge commit, que es lo que la clase delivery-state exige a un
header que afirma la entrega, y la suite de este unit audita este archivo como cualquier otro:
`bash .github/scripts/tests/odd-status-headers.test.sh` sigue reportando los mismos `82 record(s)`, los
mismos `25 passed, 0 failed` y ningún `PROBLEM`, ahora con **116** claims de merge commit en vez de 115 y
con el header reparado dentro del conteo: el hash `4b7212c` de arriba es uno de los que la mitad de
ancestry resuelve y verifica contra `origin/main`. Que el archivo no se salva por estar del lado del
guard se midió, no se supuso: el mismo párrafo con la frase de espera y sólo `#480` — sin el hash
detrás — hace fallar el audit real de la suite, que es el caso de `L8` con el signo al revés.
