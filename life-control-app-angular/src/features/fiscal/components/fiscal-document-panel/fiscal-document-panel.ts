import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { of, switchMap } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { httpErrorMessage, unwrapHttpError } from '@shared/data';
import type { ApiError } from '@shared/models';
import { ErrorBanner } from '@shared/ui';
import { CompanyService } from '@features/companies/companies/data/company.service';
import type { Company } from '@features/companies/companies/models/company.models';
import { CustomerService } from '@features/customers/data/customer.service';
import type { Customer } from '@features/customers/models/customer.models';
import {
  FISCAL_PROFILE_FIELDS,
  fiscalProfileViolations,
} from '@features/customers/validation/fiscal-profile-complete';
import { SO_STATUS_LABELS } from '@features/sales/sales-orders/data/status-config';
import { FiscalDocumentService } from '../../data/fiscal-document.service';
import type {
  FiscalDocument,
  FiscalDocumentIssueResult,
  StampedFiscalDocument,
} from '../../data/fiscal-document.models';

/**
 * The one status a sale must reach before it can be invoiced (`S1`), read from the sale's own vocabulary.
 *
 * `features/sales/sales-orders/data/status-config.ts` owns the status names, and `Completed` is the label that
 * map states for the terminal status a sale reaches once it has been charged — the other terminal one,
 * `Cancelled`, ends a sale that will never be invoiced. Taking the string from the map instead of typing it
 * here keeps one vocabulary: the sale screen and this panel cannot end up naming two different statuses.
 */
export const FISCAL_ISSUABLE_STATUS: string = SO_STATUS_LABELS['Completed'];

/** The two halves of a document's identity that a screen has to be able to name (`S1`). */
type ProfileSide = 'issuer' | 'receiver';

/**
 * What one fiscal profile's read answered.
 *
 * `reading` and `complete` are separate states instead of a booleans-and-placeholder pair because the panel
 * may not offer a stamp it has not confirmed is possible (`S1`), and "not answered yet" is not "incomplete":
 * the first shows a progress bar, the second names the facts that are missing.
 */
type FiscalProfileRead =
  | { readonly state: 'reading' }
  | { readonly state: 'complete' }
  | { readonly state: 'incomplete'; readonly facts: readonly string[] }
  | { readonly state: 'unreadable'; readonly message: string };

/** One side of the document that cannot be stated, and why — the thing `S1` has to say out loud. */
export interface FiscalProfileGap {
  readonly side: ProfileSide;
  readonly headline: string;
  readonly facts: readonly string[];
}

/** The two sentences a gap can open with, per side: a missing fact and an unread profile are not the same. */
const GAP_HEADLINES: Record<ProfileSide, Record<'incomplete' | 'unreadable', string>> = {
  issuer: {
    incomplete: 'El emisor no puede emitir el documento:',
    unreadable: 'No se pudo leer el perfil fiscal del emisor:',
  },
  receiver: {
    incomplete: 'El receptor no puede recibir el documento:',
    unreadable: 'No se pudo leer el perfil fiscal del receptor:',
  },
};

/** What the panel reports once a stamp attempt answered, said as the answer's own two outcomes (`S2`). */
function issueReportOf(answer: FiscalDocumentIssueResult): string {
  const { documentId, uuidFiscal } = answer.document;

  return answer.stampedByThisAttempt
    ? `Este intento timbró el documento ${documentId} (UUID fiscal ${uuidFiscal}).`
    : `Ese intento ya tenía el documento ${documentId} (UUID fiscal ${uuidFiscal}): no se timbró de nuevo.`;
}

/**
 * The API's own sentence for a refusal, which is what `S1`'s screen shows instead of a banner of its own.
 *
 * `ApiError.message` is the server's words — the one sentence that names the fact it refused — and it is
 * preferred over any mapping this screen could make. The shared map is the fallback for an answer that carries
 * no such body (a transport failure, a proxy's HTML), which is the only case where there is no server sentence
 * to show; that is not the "generic banner" the record refuses, it is the absence of anything better.
 */
function apiMessage(error: unknown): string {
  const body = unwrapHttpError(error)?.error as Partial<ApiError> | null | undefined;
  const message = body?.message;

  return typeof message === 'string' && message.trim() !== '' ? message : httpErrorMessage(error);
}

/** Whether a profile states a fact: blank is not a declaration, the same reading `CustomerRequest` normalizes. */
function states(value: string | null | undefined): boolean {
  return typeof value === 'string' && value.trim() !== '';
}

/**
 * The issuer's half of the document: the regime and the place of issue, the two facts `#932`'s `S1` names.
 *
 * `regimenFiscal` and `fiscalZipCode` are nullable on the company on purpose — they are required to issue and
 * never to sell (`V102`) — so a company that states neither is a complete row and an incomplete issuer, which
 * is exactly the state this panel reports instead of offering a stamp the API would refuse.
 */
function issuerReadOf(company: Company): FiscalProfileRead {
  const facts: string[] = [];
  if (!states(company.regimenFiscal)) {
    facts.push('La empresa no declara su régimen fiscal.');
  }
  if (!states(company.fiscalZipCode)) {
    facts.push('La empresa no declara su lugar de expedición (código postal fiscal).');
  }

  return facts.length > 0 ? { state: 'incomplete', facts } : { state: 'complete' };
}

/**
 * The receiver's half of the document: the customer's fiscal profile, judged by the tree's own copy of the
 * all-or-nothing rule.
 *
 * **Why "no violations" is not enough.** `fiscalProfileViolations` answers `{}` for a profile that states
 * **none** of the four facts as well as for a complete one, because at the till a cash customer is a complete
 * customer (`S3` of `odd/tasks/customers-fiscal-form.md`). A fiscal document is the other question: the API
 * asks `Customer#fiscalProfileComplete()` — "all four stated" — and refuses the sale when it is false
 * (`FiscalDocumentSnapshotWriter#requireReceiver`). So the receiver is complete here when the rule finds no
 * violation **and** something is stated, which is that same predicate stated with the rule the client has.
 */
function receiverReadOf(customer: Customer): FiscalProfileRead {
  const violations = Object.values(fiscalProfileViolations(customer));
  if (violations.length > 0) {
    return { state: 'incomplete', facts: violations };
  }

  const declared = FISCAL_PROFILE_FIELDS.some((field) => states(customer[field]));
  if (declared) {
    return { state: 'complete' };
  }

  return {
    state: 'incomplete',
    facts: [
      'El cliente no declara ningún dato fiscal: el documento exige razón social, régimen fiscal, código postal fiscal y uso de CFDI.',
    ],
  };
}

/**
 * One sale's fiscal document, and the one action that creates it (`S1`, `S2` of `odd/tasks/fiscal-document-screens.md`).
 *
 * **Two reads, one command, and nothing irreversible on its own.** The panel reads the sale's document
 * (`GET /api/fiscal-documents/by-sale/{id}`, whose `204` is the "not invoiced" state and not a failure) and the
 * two fiscal profiles a stamp consumes, and it offers the issue action only when the sale is completed and both
 * profiles are complete, naming the side that is not. **It never issues by itself**: a stamp is irreversible, so
 * the only path to it is the operator's click.
 *
 * **The attempt key is the operator's intent, kept across failures.** It is minted on the first click and reused
 * on every retry of that intent, so a retry after a timeout replays the attempt the API already recorded instead
 * of stamping a second document (`S2`); it is cleared only when an attempt answered, because the next click is a
 * new intent. A failure never regenerates it silently — that is the one thing that would break the replay.
 *
 * **What it does not do.** It does not capture or fix a fiscal profile (`N1`): a gap is named and the panel stops
 * there. It does not re-derive the issuer's company from a context the app does not have — `CurrentUser` exposes
 * only `sub`, so the company comes from the claim-scoped selector read, two calls deep — and it does not claim
 * anything the read does not carry: no authority code, no XML, no folio a stamp answer never stated.
 */
@Component({
  selector: 'app-fiscal-document-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ErrorBanner, MatButtonModule, MatIconModule, MatProgressBarModule],
  templateUrl: './fiscal-document-panel.html',
  styleUrl: './fiscal-document-panel.scss',
})
export class FiscalDocumentPanel implements OnInit {
  private readonly fiscalDocumentService = inject(FiscalDocumentService);
  private readonly companyService = inject(CompanyService);
  private readonly customerService = inject(CustomerService);
  private readonly destroyRef = inject(DestroyRef);

  /** The sale whose document is being read and issued. */
  readonly saleId = input.required<string>();

  /** The sale's status, as the sale itself states it — the gate `S1` puts in front of the action. */
  readonly saleStatus = input.required<string>();

  /**
   * The receiver the sale names, or `null` for a counter sale.
   *
   * Defaulted rather than required because `null` is a state the panel reports (a sale with no customer cannot
   * be invoiced) and not a missing input.
   */
  readonly customerId = input<string | null>(null);

  /** Whether the sale is in the one status a document may be issued for. */
  readonly saleCompleted = computed(() => this.saleStatus() === FISCAL_ISSUABLE_STATUS);

  private readonly _reading = signal(true);
  private readonly _readError = signal<string | null>(null);
  /** The document the read answered, or `null` — which is the sale's "never invoiced", not a failure. */
  private readonly documentRead = signal<FiscalDocument | null>(null);
  /** The document a stamp answered, for the identity the read may still be missing after a failed re-read. */
  private readonly documentStamped = signal<StampedFiscalDocument | null>(null);
  private readonly _report = signal<string | null>(null);

  private readonly issuer = signal<FiscalProfileRead>({ state: 'reading' });
  private readonly receiver = signal<FiscalProfileRead>({ state: 'reading' });

  private readonly _issuing = signal(false);
  private readonly _issuingFailure = signal<string | null>(null);

  /**
   * The attempt key of the operator's current intent, or `null` when there is nothing to replay.
   *
   * A plain field and not a signal: nothing renders it, and it must survive every re-render the error path
   * causes. `issue()` mints it once and never overwrites it while an intent is unresolved.
   */
  private attemptKey: string | null = null;

  /** Whether the sale's document is still being read. */
  readonly loading = this._reading.asReadonly();

  /** The message of a failed read, or `null`. */
  readonly loadFailure = this._readError.asReadonly();

  /**
   * The document's identity, from the read when it answered and from the stamp's answer otherwise.
   *
   * The two sources state different facts and the difference is kept: the read states the folio and the
   * cancellation block, a stamp answer states neither, so the folio is only rendered where the read carried it
   * ({@link documentRead}).
   */
  readonly identity = computed<{
    documentId: string;
    uuidFiscal: string;
    stampedAt: string;
  } | null>(() => {
    const read = this.documentRead();
    if (read) {
      return {
        documentId: read.documentId,
        uuidFiscal: read.uuidFiscal,
        stampedAt: read.stampedAt,
      };
    }

    const stamped = this.documentStamped();
    return stamped
      ? {
          documentId: stamped.documentId,
          uuidFiscal: stamped.uuidFiscal,
          stampedAt: stamped.stampedAt,
        }
      : null;
  });

  /**
   * The folio as the **read** stated it, or `undefined` when no read has answered.
   *
   * The two absences are different facts and are not collapsed: `null` is the read saying the document declares
   * no folio, while `undefined` is a stamp answer that never carried the field. Rendering them as one sentence
   * would report a fact nobody stated.
   */
  readonly folioLabel = computed<string | undefined>(() => {
    const read = this.documentRead();
    return read ? (read.folio ?? 'Sin folio') : undefined;
  });

  /** What the last stamp attempt answered, worded, or `null` before the first one (`S2`). */
  readonly issueReport = this._report.asReadonly();

  /** The API's own sentence for the last refused attempt, or `null`. */
  readonly issuingFailure = this._issuingFailure.asReadonly();

  /** Whether both profile reads have answered, in either direction. */
  readonly profilesReading = computed(
    () => this.issuer().state === 'reading' || this.receiver().state === 'reading',
  );

  /** The sides that cannot be stated, in the order the document reads them. */
  readonly profileGaps = computed<FiscalProfileGap[]>(() => {
    const gaps: FiscalProfileGap[] = [];
    const issuer = gapOf('issuer', this.issuer());
    const receiver = gapOf('receiver', this.receiver());
    if (issuer) gaps.push(issuer);
    if (receiver) gaps.push(receiver);
    return gaps;
  });

  /**
   * Whether the issue action is offered at all (`S1`): a completed sale, no document yet, and both profiles
   * confirmed complete. A profile still being read counts as confirmed by nobody, so nothing is offered.
   */
  readonly offersIssue = computed(
    () =>
      this.saleCompleted() &&
      this.identity() === null &&
      !this._reading() &&
      this._readError() === null &&
      !this.profilesReading() &&
      this.profileGaps().length === 0,
  );

  /** Whether an attempt is in flight, for the control's own state. */
  readonly issuing = this._issuing.asReadonly();

  /** Whether the action may be clicked now: offered, and no attempt in flight. */
  readonly canIssue = computed(() => this.offersIssue() && !this._issuing());

  ngOnInit(): void {
    this.loadDocument();
    this.loadIssuer();
    this.loadReceiver();
  }

  /**
   * Reads the sale's document — the only read that can say "already invoiced", and the reason a `204` is a state.
   *
   * Public because the failed-read path offers it again: a read that could not answer is not a stamp and costs
   * nothing to repeat.
   */
  loadDocument(): void {
    this._reading.set(true);
    this._readError.set(null);

    this.fiscalDocumentService
      .readBySale(this.saleId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (document) => {
          this.documentRead.set(document);
          this._reading.set(false);
        },
        error: (error: unknown) => {
          this._reading.set(false);
          this._readError.set(apiMessage(error));
        },
      });
  }

  /**
   * Issues the sale's document under the operator's attempt key (`S1`), or does nothing when it may not.
   *
   * **The stamp is irreversible and this is its only entrance**: no constructor path, no `ngOnInit`, no input
   * change and no retry calls this method — a click does. The key is minted here, once, and kept through every
   * failure so the retry replays the same attempt; it is cleared when an attempt answers, because the next click
   * is a new intent.
   */
  issue(): void {
    if (!this.canIssue()) {
      return;
    }

    this.attemptKey ??= crypto.randomUUID();
    const attemptKey = this.attemptKey;

    this._issuing.set(true);
    this._issuingFailure.set(null);

    this.fiscalDocumentService
      .issue(this.saleId(), attemptKey)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (answer) => {
          this._issuing.set(false);
          // The attempt answered, so the next intent is a new one. A failure below this line keeps the key.
          this.attemptKey = null;
          this.documentStamped.set(answer.document);
          this._report.set(issueReportOf(answer));
          // The stamp's answer states no folio and no cancellation block, so the document is read again
          // instead of a partial identity being rendered as if it were the read's. A re-read that fails
          // leaves the stamp's own identity on screen — the attempt already succeeded, and saying so does
          // not depend on the read.
          this.loadDocument();
        },
        error: (error: unknown) => {
          this._issuing.set(false);
          // The key stays: the operator's intent has not answered, and regenerating it here is what would
          // let a retry stamp a second document.
          this._issuingFailure.set(apiMessage(error));
        },
      });
  }

  /**
   * Reads the issuer's half: the company the JWT's `company_id` claim resolves to.
   *
   * Two calls and no company context, because there is none to read: `CurrentUser` exposes only `sub`, so the
   * claim-scoped selector read is the only door to the row — and it carries no role gate, which is what lets a
   * seller's screen see the issuer's profile at all. The selector answers the caller's own company; the first
   * row is that company.
   */
  private loadIssuer(): void {
    this.issuer.set({ state: 'reading' });

    this.companyService
      .getCompanySelector(0, 1)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        switchMap((page) => {
          const company = page.content[0];
          return company
            ? this.companyService.getCompanyById(company.id)
            : of<Company | null>(null);
        }),
      )
      .subscribe({
        next: (company) => {
          this.issuer.set(
            company
              ? issuerReadOf(company)
              : {
                  state: 'incomplete',
                  facts: ['No se resolvió la empresa del emisor: la sesión no declara compañía.'],
                },
          );
        },
        // A read that failed is not a profile that is empty: the difference is the difference between "fix the
        // company" and "reload the screen", so the failure keeps its own state.
        error: (error: unknown) =>
          this.issuer.set({ state: 'unreadable', message: apiMessage(error) }),
      });
  }

  /**
   * Reads the receiver's half: the sale's own `customerId`, through the customer read.
   *
   * A sale that names no customer is a counter sale, and a counter sale cannot be invoiced — the API refuses it
   * (`FiscalDocumentSnapshotWriter#requireReceiver`). That is reported here rather than asked for, because
   * capturing a receiver is not this unit's job (`N1`).
   */
  private loadReceiver(): void {
    this.receiver.set({ state: 'reading' });

    const customerId = this.customerId();
    if (!customerId) {
      this.receiver.set({
        state: 'incomplete',
        facts: ['La venta no declara cliente, y un documento fiscal necesita un receptor.'],
      });
      return;
    }

    this.customerService
      .getCustomerById(customerId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (customer) => this.receiver.set(receiverReadOf(customer)),
        error: (error: unknown) =>
          this.receiver.set({ state: 'unreadable', message: apiMessage(error) }),
      });
  }
}

/** One profile read as the gap it is, or `null` when that side has nothing to report. */
function gapOf(side: ProfileSide, read: FiscalProfileRead): FiscalProfileGap | null {
  switch (read.state) {
    case 'reading':
    case 'complete':
      return null;
    case 'incomplete':
      return { side, headline: GAP_HEADLINES[side].incomplete, facts: read.facts };
    case 'unreadable':
      return { side, headline: GAP_HEADLINES[side].unreadable, facts: [read.message] };
  }
}
