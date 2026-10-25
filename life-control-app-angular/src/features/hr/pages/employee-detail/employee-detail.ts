import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatDialog } from '@angular/material/dialog';
import { catchError, map } from 'rxjs/operators';
import { Observable, of } from 'rxjs';
import { ConfirmDialog, ConfirmDialogData, ErrorBanner, PageHeader } from '@shared/ui';
import { httpErrorMessage, unwrapHttpError } from '@shared/data';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CurrentUser } from '@core/security/current-user';
import { EmployeeService } from '../../data/employee.service';
import { ContractService } from '../../data/contract.service';
import { ScopeAssignmentService } from '../../data/scope-assignment.service';
import { AccessProvisioningService } from '../../data/access-provisioning.service';
import { employeeStatusLabel } from '../../data/employee-status';
import {
  ContractHistory,
  isContractCurrent,
} from '../../components/contract-history/contract-history';
import { ScopeAssignmentsSection } from '../../components/scope-assignments-section/scope-assignments-section';
import { AccessSection } from '../../components/access-section/access-section';
import { Contract } from '../../models/contract.models';
import { ScopeAssignment } from '../../models/scope-assignment.models';
import { Employee } from '../../models/employee.models';
import {
  AccessProvisioningOverview,
  AccessProvisioningTaskView,
} from '../../models/access-provisioning.models';
import {
  AccessReviewDialog,
  AccessReviewDialogData,
  AccessReviewDialogResult,
} from '../../components/access-review-dialog/access-review-dialog';
import {
  ContractDialog,
  ContractDialogData,
  ContractDialogResult,
} from '../../components/contract-dialog/contract-dialog';
import {
  ScopeAssignmentDialog,
  ScopeAssignmentDialogData,
  ScopeAssignmentDialogResult,
} from '../../components/scope-assignment-dialog/scope-assignment-dialog';
import { ACCESS_READ_ROLES, EMPLOYEE_WRITE_ROLES, hasAnyClientRole } from '@core/security/roles';

/**
 * The page's tabs, in the order they render, keyed by the values `?tab=` carries.
 *
 * `accesos` is conditional: a caller without `ACCESS_READ_ROLES` gets the first three and nothing
 * else (record T96), so the array is resolved through {@link EmployeeDetail.visibleTabs} instead of
 * used directly.
 *
 * There is one assignment tab, not two: `tiendas` was merged into `alcance` once the store became a
 * level of the scope instead of a table of its own (`S1`), so it is no longer a member of this list
 * while {@link EmployeeDetail.readTab} keeps the old deep links landing on it.
 */
const DETAIL_TABS = ['detalle', 'contratos', 'alcance', 'accesos'] as const;
type DetailTab = (typeof DETAIL_TABS)[number];

/** The tabs every authenticated caller sees; `accesos` joins them behind its role gate. */
const OPEN_TABS: readonly DetailTab[] = ['detalle', 'contratos', 'alcance'];

/**
 * Read-only detail screen of one employee: `/hr/employees/:id`.
 *
 * **The company is resolved exactly as `employee-list` resolves it** — an inline
 * `mat-select` fed by `CompanyService.getCompanySelector(0, 1000)`, the claim-authorized selector
 * read, keyed by
 * `company.id`, seeded from the `companyId` query parameter and written back to
 * the URL on change. That is the same mechanism, not a new one: the detail is
 * reached from a list row that already carries the company, and the employee read
 * is company-scoped (`GET …/companies/{companyId}/employees/{id}`), so the page
 * needs a company id before it can ask for anything. The selector is also what
 * makes a bare deep link (no `companyId`) recoverable instead of a dead end, and
 * no request is issued until a company is selected — the list's own rule.
 *
 * **The sections are tabs, one per concern** — `Detalle`, `Contratos`, `Alcance` and `Accesos` — and
 * each write control lives in the tab that owns the act it starts: `Editar` opens the edit form from
 * `Detalle`, `Nuevo contrato` / `Cerrar contrato vigente` from `Contratos`, `Asignar alcance` /
 * `Cerrar` from the merged scope section, and the access writes from `Accesos`. The tab is read once
 * from `?tab=` (the same channel `companyId` travels on) and written back with `replaceUrl`, because
 * a tab is a view of this page and not a history entry; an unrecognised or unrenderable value falls
 * back to `Detalle`. The reads are asked for by the page, not by a tab, so switching tabs costs no
 * request: Material only attaches the body of the tab on screen.
 *
 * The page owns the four reads (`getEmployee` + `getContracts` + the scope assignments + the access
 * overview) **independently**, so a failure of one degrades only its half of the screen: the
 * employee read keeps the three states — loading, error and **not-found** (a 404 is told apart from
 * every other failure, because the employee may simply not exist in the selected company) — while the
 * contracts, scope and access tabs own their own error and retry. Joining the reads would let a
 * failed contracts call discard a successfully read employee, which is the coupling this split
 * removes.
 *
 * The **"Puesto actual"** of the `Detalle` tab is the `D15`/`G15` fact only this screen can
 * show: the position comes from the employee's **current contract**, which the
 * list response does not carry. It resolves through the same
 * `isContractCurrent` predicate the history's validity chip uses, so the card
 * and the table can never disagree.
 *
 * There are two contract write controls, both gated on `EMPLOYEE_WRITE_ROLES` and both in the
 * `Contratos` tab: **"Nuevo contrato"** opens the create mode of {@link ContractDialog}, and **"Cerrar
 * contrato vigente"** is offered only while the employee actually has a current
 * contract (`isContractCurrent`) and opens the dialog's close mode. The page does
 * not write: it opens the dialog (`D71`) and reloads the contracts read on a real outcome, which is
 * the read the card's "Puesto actual" also derives from. A **create** act can also carry a store
 * assignment (`D8`), so it reloads the scope read too and reports a failed second call as the
 * partial outcome it is (`D9`) instead of hiding it.
 *
 * The **Alcance** tab is one surface for the whole assignment history, and the contract dialog's
 * optional store is only its fourth level (`S1`, `S2`): país, región, zona y tienda son asignables por
 * empleado y el nivel de empresa no lo es — una persona, una empresa (`S3`) — así que
 * {@link ScopeAssignmentsSection} renders the employee's company as the **derived root** of the tree
 * and one row per assignment with the company/country/region/zone chain the token will carry.
 * {@link ScopeAssignmentDialog} is the single dialog: it offers the four assignable levels and hides
 * and clears the levels below the chosen one, so the form can never submit two nodes. The write gate
 * is `EMPLOYEE_WRITE_ROLES`, the pair `EmployeeScopeAssignmentController` carries (`S4`), and the
 * level the dialog preselects is only a hint taken from the employee's own scoped roles: the level is
 * an affordance, never a gate, so no authorization rule is invented in the UI.
 *
 * The **Acceso** tab (W5d-C) is the fourth read and it follows the same independence: it is
 * keyed on the selected company and the route id and gated on `ACCESS_READ_ROLES`, so a caller
 * without `lc-admin`/`lc-employee-access` renders no tab and issues no request (record T96). It
 * renders through {@link AccessSection} and the page owns its five intents: `apply`, `deactivate`
 * (confirmed through the shared `ConfirmDialog`), `retry` and `resend` call the service, and
 * `decisionRequested` opens the shared {@link AccessReviewDialog}. Every write **adopts the overview
 * the endpoint answers with** as the new state instead of triggering a second `GET` (record T75) —
 * see {@link runAccessWrite} — and a failure is reported with the server's own message, because the
 * refusals here are named 409s the operator must read.
 */
@Component({
  selector: 'app-employee-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatTabsModule,
    PageHeader,
    ErrorBanner,
    ContractHistory,
    ScopeAssignmentsSection,
    AccessSection,
  ],
  templateUrl: './employee-detail.html',
  styleUrl: './employee-detail.scss',
})
export class EmployeeDetail {
  private readonly companyService = inject(CompanyService);
  private readonly employeeService = inject(EmployeeService);
  private readonly contractService = inject(ContractService);
  private readonly scopeAssignmentService = inject(ScopeAssignmentService);
  private readonly accessService = inject(AccessProvisioningService);
  private readonly currentUser = inject(CurrentUser);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * `lc-employee` and `lc-admin` reach the contract write endpoints; every other
   * authenticated caller reads the detail but must not render a control the API
   * would refuse.
   */
  readonly canWrite = hasAnyClientRole(EMPLOYEE_WRITE_ROLES);

  /**
   * The Access read/write gate: `lc-admin` and `lc-employee-access` (record T96). A caller without
   * either renders no Access section and issues no request for it, because every access endpoint
   * carries that very `@PreAuthorize` and would answer 403.
   */
  readonly canReadAccess = hasAnyClientRole(ACCESS_READ_ROLES);

  /** The caller's own `sub`, so `approve` can be hidden on their own request (O3). */
  get currentUserId(): string | null {
    return this.currentUser.sub;
  }

  /** The route's `:id`; the page reads exactly one employee. */
  readonly employeeId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  /**
   * The tabs this caller actually sees: `Accesos` only exists behind `ACCESS_READ_ROLES`.
   *
   * Everything tab-related indexes into this list, never into {@link DETAIL_TABS}, so a caller
   * without the access roles cannot land on a tab it does not render and the index Material is
   * given always maps to a tab on screen.
   */
  readonly visibleTabs = computed<readonly DetailTab[]>(() =>
    this.canReadAccess ? DETAIL_TABS : OPEN_TABS,
  );

  /**
   * The active tab, seeded from `?tab=` and kept in the URL on every change (like `companyId`).
   *
   * An absent, unrecognised or unrenderable value falls back to `Detalle`: a stale link must land on
   * the page, not on a blank tab. The selection is read once from the route snapshot, the same way
   * `selectedCompanyId` is, and the change is written with `replaceUrl` — a tab is a view of the
   * page, not a history entry, so Back leaves the detail instead of walking the tabs.
   */
  readonly activeTab = signal<DetailTab>(
    this.readTab(this.route.snapshot.queryParamMap.get('tab')),
  );

  /** The index Material renders as selected; derived, so it can never disagree with the URL. */
  readonly selectedTabIndex = computed(() =>
    Math.max(0, this.visibleTabs().indexOf(this.activeTab())),
  );

  /** The selector's options, the same read `employee-list` performs. */
  readonly companies = toSignal(
    this.companyService.getCompanySelector(0, 1000).pipe(map((page) => page.content)),
    { initialValue: [] },
  );

  /** Company selection: seeded from the URL and kept there, like the list. */
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );

  /**
   * The employee read, re-run whenever the selected company changes. No company
   * (or no route id) means no request at all.
   */
  readonly employeeResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      const employeeId = this.employeeId();
      if (!companyId || !employeeId) {
        return undefined;
      }
      return { companyId, employeeId };
    },
    stream: ({ params }) => this.employeeService.getEmployee(params.companyId, params.employeeId),
  });

  /**
   * The contracts read, independent from the employee one: a failure here leaves
   * the employee card standing and is shown by the `Contratos` section alone.
   */
  readonly contractsResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      const employeeId = this.employeeId();
      if (!companyId || !employeeId) {
        return undefined;
      }
      return { companyId, employeeId };
    },
    stream: ({ params }) => this.contractService.getContracts(params.companyId, params.employeeId),
  });

  /**
   * The scope-assignment read (`S1`, `S2`): one read for the whole merged tab, country through
   * store, independent from the other three so a failure here leaves the employee card, the
   * contracts and the access section standing.
   *
   * The whole history is asked for (`includeDisabled`), exactly as before: a soft-deleted assignment
   * is part of the record, and the section distinguishes it from a closed one.
   */
  readonly scopeAssignmentsResource = rxResource({
    params: () => {
      const companyId = this.selectedCompanyId();
      const employeeId = this.employeeId();
      if (!companyId || !employeeId) {
        return undefined;
      }
      return { companyId, employeeId };
    },
    stream: ({ params }) =>
      this.scopeAssignmentService.getAssignments(params.companyId, params.employeeId, true),
  });

  /**
   * The access-provisioning read, independent from the other three so a failure degrades only the
   * Access section. It is keyed on the selected company and the route id, and it is **gated on the
   * role** (`ACCESS_READ_ROLES`): a caller without `lc-admin`/`lc-employee-access` produces no
   * params, so no request is ever issued for a section it cannot see (record T96).
   */
  readonly accessResource = rxResource({
    params: () => {
      if (!this.canReadAccess) {
        return undefined;
      }
      const companyId = this.selectedCompanyId();
      const employeeId = this.employeeId();
      if (!companyId || !employeeId) {
        return undefined;
      }
      return { companyId, employeeId };
    },
    stream: ({ params }) => this.accessService.getOverview(params.companyId, params.employeeId),
  });

  /** The page-level loading gate follows the employee read; contracts load in its own section. */
  readonly loading = this.employeeResource.isLoading;

  /**
   * The loaded employee, or `null` while loading and on any failure.
   *
   * Guarded by `hasValue()` on purpose: reading `.value()` in an error state
   * throws (rxResource wraps a non-`Error` failure, and `HttpErrorResponse` is
   * one), so the error states below read `.error()` instead.
   */
  readonly employee = computed<Employee | null>(() =>
    this.employeeResource.hasValue() ? (this.employeeResource.value() ?? null) : null,
  );

  readonly contracts = computed<Contract[]>(() =>
    this.contractsResource.hasValue() ? (this.contractsResource.value() ?? []) : [],
  );

  /** The contracts section's loading flag; drives only that section's copy. */
  readonly contractsLoading = this.contractsResource.isLoading;

  readonly scopeAssignments = computed<ScopeAssignment[]>(() =>
    this.scopeAssignmentsResource.hasValue() ? (this.scopeAssignmentsResource.value() ?? []) : [],
  );

  /** The scope section's loading flag; drives only that section's copy. */
  readonly scopeAssignmentsLoading = this.scopeAssignmentsResource.isLoading;

  /**
   * The selected company's name, as the selector carries it.
   *
   * The merged section renders it as the **derived root** of the tree (`S3`): the company is a fact
   * of the employee, so there is no company assignment to read and no view that owns the name. It is
   * `null` until a company is selected, and the section renders that as a placeholder rather than
   * inventing a name.
   */
  readonly selectedCompanyName = computed<string | null>(() => {
    const companyId = this.selectedCompanyId();
    if (!companyId) {
      return null;
    }
    return this.companies().find((candidate) => candidate.id === companyId)?.companyName ?? null;
  });

  /** The access overview, or `null` while loading and on any failure. */
  readonly access = computed<AccessProvisioningOverview | null>(() =>
    this.accessResource.hasValue() ? (this.accessResource.value() ?? null) : null,
  );

  /**
   * The employee's status name, as the employee read carries it, or `null` before it resolves.
   *
   * The Access section needs it to hide `apply`/`resend` for a `Terminated` employee (record T84);
   * the access overview carries no status, so the page — which already reads the employee — supplies
   * it. This is the employee read, not a second call.
   */
  readonly employeeStatusName = computed<string | null>(() => this.employee()?.statusName ?? null);

  /**
   * The selected company's `emailDomain`, read through the id-addressed company read.
   *
   * The selector projection carries only `{id, companyKey, companyName}` and the access overview
   * carries no domain at all, so the page reads the one company it needs instead of keeping a
   * thousand-row list — a read the read-only hierarchy roles are not allowed to ask for anyway.
   * That is one id-addressed request per selection, the same call the purchase-order cascade
   * already makes for its company detail panel.
   *
   * A failed read leaves the domain **unresolved** (`undefined`), which must not fire the gate:
   * only `null`/blank is a company with no deliverable domain (record T68/T85).
   */
  private readonly selectedCompanyDomain = rxResource({
    params: () => this.selectedCompanyId() || undefined,
    stream: ({ params: companyId }) =>
      this.companyService.getCompanyById(companyId).pipe(
        map((company) => company.emailDomain ?? null),
        catchError(() => of(undefined)),
      ),
    defaultValue: undefined as string | null | undefined,
  });

  /**
   * The selected company's `emailDomain`, or `undefined` while that read has not resolved.
   *
   * The Access section needs it to hide `apply`/`resend` when the company has no deliverable domain
   * (record T68/T85); the access overview carries no domain, so the page supplies the fact from its
   * own id-addressed company read. `undefined` (unresolved) must not fire the gate, while
   * `null`/blank is a missing domain and does.
   */
  readonly selectedCompanyEmailDomain = computed<string | null | undefined>(() =>
    this.selectedCompanyDomain.value(),
  );

  /** The Access section's own loading flag. */
  readonly accessLoading = this.accessResource.isLoading;

  /** A failed access write, as the page resolved it; `null` when the last write succeeded. */
  readonly accessActionError = signal<string | null>(null);

  /**
   * A failed close write, as the service mapped it.
   *
   * A failed **assign** never reaches the page: the dialog stays open and reports it in its own
   * banner, so this is only ever the close route's message. A failed assignment inside the contract
   * activation act is a different fact and gets its own signal below.
   */
  readonly scopeAssignmentActionError = signal<string | null>(null);

  /**
   * The partial outcome of the contract activation act (`D9`): the contract was created and the
   * store was not assigned.
   *
   * Named as two facts, because that is what happened — a banner implying nothing was saved would be
   * false. The assign dialog of the `Alcance` tab is the retry path (`S9`), so the message points
   * there; it clears once that retry succeeds (`onAssignScope`) or when a later activation act
   * starts from a clean slate.
   */
  readonly partialAssignmentError = signal<string | null>(null);

  /**
   * A 404 is its own state: the employee does not exist in the selected company
   * (or the caller cannot see it), which is not the same as a failed read and
   * must not offer a retry that can never succeed.
   */
  readonly notFound = computed(
    () => unwrapHttpError(this.employeeResource.error())?.status === 404,
  );

  /** The banner copy of a failure that is not a not-found; `null` otherwise. */
  readonly loadError = computed(() => {
    const error = this.employeeResource.error();
    if (!error || this.notFound()) {
      return null;
    }
    return httpErrorMessage(error);
  });

  /** The contracts section's own error copy; `null` while loading, on success or before selection. */
  readonly contractsError = computed(() => {
    const error = this.contractsResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  /** The scope section's own error copy; `null` while loading, on success or before selection. */
  readonly scopeAssignmentsError = computed(() => {
    const error = this.scopeAssignmentsResource.error();
    return error ? httpErrorMessage(error) : null;
  });

  /**
   * The employee's own role names, as the **access overview** reports them: what the account holds
   * plus what the position requires.
   *
   * They exist only for a caller that may read the access overview (`ACCESS_READ_ROLES`), so this is
   * empty otherwise and the assign dialog simply offers the four levels with no hint. They are
   * **not** used to decide anything: the dialog only orders and preselects the level with them (see
   * `suggestedLevelFromRoles`), because which level an operator may write is the API's decision and
   * inventing an authorization rule in the UI is exactly what this unit avoids.
   */
  readonly employeeScopeRoles = computed<string[]>(() => {
    const overview = this.access();
    if (!overview) {
      return [];
    }
    return Array.from(new Set([...overview.currentRoles, ...overview.requiredRoles]));
  });

  /**
   * The Access section's own error copy; `null` while loading, on success or before selection.
   *
   * The API's envelope message wins when it carries one; the shared taxonomy is the fallback. Every
   * access failure the operator can act on is a named refusal, so a generic copy would hide it.
   */
  readonly accessError = computed(() => {
    const error = this.accessResource.error();
    if (!error) {
      return null;
    }
    return this.serverEnvelope(error) ?? httpErrorMessage(error);
  });

  /** The header's current contract, resolved with the history's own predicate. */
  readonly currentContract = computed<Contract | null>(
    () => this.contracts().find(isContractCurrent) ?? null,
  );

  /** The current position, or an em dash when the employee has no current contract. */
  readonly currentPositionName = computed(() => this.currentContract()?.positionName ?? '—');

  readonly subtitle = computed<string | undefined>(() => {
    const employee = this.employee();
    return employee ? this.fullName(employee) : undefined;
  });

  readonly statusLabel = computed(() => {
    const employee = this.employee();
    return employee ? employeeStatusLabel(employee.statusName) : '';
  });

  /** `firstName` plus both last names, tolerating the nullable maternal one. */
  fullName(employee: Employee): string {
    return [employee.firstName, employee.paternalLastName, employee.maternalLastName]
      .filter((part): part is string => !!part)
      .join(' ');
  }

  /** Keeps the selection in the URL, exactly as the list does. */
  onCompanyChange(companyId: string): void {
    const next = companyId || null;
    this.selectedCompanyId.set(next);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { companyId: next },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /**
   * Switches tab and keeps the selection in `?tab=` (`companyId` is merged, never dropped).
   *
   * The equality check is the feedback-loop guard: `mat-tab-group` emits `selectedIndexChange` after
   * `[selectedIndex]` moves, so writing a value the URL already carries would navigate into itself.
   */
  onTabChange(index: number): void {
    const requested = this.visibleTabs()[index];
    if (!requested || requested === this.activeTab()) {
      return;
    }
    this.activeTab.set(requested);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab: requested },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /** The tab `?tab=` asks for, or `Detalle` when it names none of the ones this caller renders. */
  private readTab(requested: string | null): DetailTab {
    const tab = (requested ?? '').trim();
    // `tiendas` was the pre-merge deep link of what is now one tab, and every link to it has to land
    // on the screen that can act instead of falling back to `Detalle` (`S1`). `tiendas` is
    // deliberately not a tab of its own, so this alias is the whole compatibility surface.
    const resolved = tab === 'tiendas' ? 'alcance' : tab;
    return this.visibleTabs().find((candidate) => candidate === resolved) ?? 'detalle';
  }

  /**
   * Opens the edit form of this employee, carrying the selected company like the list's `Editar`.
   *
   * Gated on `EMPLOYEE_WRITE_ROLES`: the edit route and the update endpoint both require it, so the
   * control is only offered where the destination would accept the caller.
   */
  onEdit(): void {
    const employeeId = this.employeeId();
    const companyId = this.selectedCompanyId();
    if (!this.canWrite || !employeeId || !companyId) {
      return;
    }
    void this.router.navigate(['/hr/employees/edit', employeeId], { queryParams: { companyId } });
  }

  /** Re-runs the employee read that failed. */
  reload(): void {
    this.employeeResource.reload();
  }

  /** Re-runs only the contracts read, the `Contratos` section's own retry. */
  retryContracts(): void {
    this.contractsResource.reload();
  }

  /** Re-runs only the scope assignments read, the `Alcance` tab's own retry. */
  retryScopeAssignments(): void {
    this.scopeAssignmentsResource.reload();
  }

  /** Re-runs only the access read, the `Acceso` section's own retry. */
  onAccessRetry(): void {
    this.accessResource.reload();
  }

  /** Asks the API for the employee's access; the page owns the call (record T95). */
  onAccessApply(): void {
    const context = this.accessContext();
    if (!context) {
      return;
    }
    this.runAccessWrite(() => this.accessService.apply(context.companyId, context.employeeId));
  }

  /** Revokes the employee's access; the page owns the call and the confirmation. */
  onAccessDeactivate(): void {
    const context = this.accessContext();
    if (!context) {
      return;
    }
    const data: ConfirmDialogData = {
      title: 'Deshabilitar el acceso',
      message:
        '¿Confirmás que querés deshabilitar el acceso de este empleado? Se le revocan los roles y los atributos de alcance, y la cuenta se deshabilita.',
      confirmLabel: 'Deshabilitar acceso',
      destructive: true,
    };
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, { data })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((confirmed) => {
        if (confirmed) {
          this.runAccessWrite(() =>
            this.accessService.deactivate(context.companyId, context.employeeId),
          );
        }
      });
  }

  /** Returns the failed open task to the queue. */
  onAccessRetryRequest(): void {
    const context = this.accessContext();
    if (!context) {
      return;
    }
    this.runAccessWrite(() => this.accessService.retry(context.companyId, context.employeeId));
  }

  /** Re-sends the activation invitation. */
  onAccessResend(): void {
    const context = this.accessContext();
    if (!context) {
      return;
    }
    this.runAccessWrite(() => this.accessService.resend(context.companyId, context.employeeId));
  }

  /**
   * Opens the shared review dialog for a gated task (record T95).
   *
   * The dialog collects the decision and performs no call; the page calls `approve`/`reject` and
   * adopts the response it answers with.
   */
  onAccessDecision(task: AccessProvisioningTaskView): void {
    const context = this.accessContext();
    const overview = this.access();
    const employee = this.employee();
    if (!context || !overview || !employee) {
      return;
    }
    const data: AccessReviewDialogData = {
      overview,
      currentUserId: this.currentUserId,
      employeeNumber: employee.employeeNumber,
      employeeName: this.fullName(employee),
    };
    this.dialog
      .open<AccessReviewDialog, AccessReviewDialogData, AccessReviewDialogResult>(
        AccessReviewDialog,
        {
          data,
        },
      )
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        // `undefined` is a bare Material dismissal and means the same as `null`: no write.
        if (!result) {
          return;
        }
        if (result.decision === 'approve') {
          this.runAccessWrite(() =>
            this.accessService.approve(context.companyId, context.employeeId, task.id),
          );
          return;
        }
        this.runAccessWrite(() =>
          this.accessService.reject(context.companyId, context.employeeId, task.id, {
            reason: result.reason,
          }),
        );
      });
  }

  /** Guards every access intent on the read/write role and on a selected company + employee. */
  private accessContext(): { companyId: string; employeeId: string } | null {
    const companyId = this.selectedCompanyId();
    const employeeId = this.employeeId();
    if (!this.canReadAccess || !companyId || !employeeId) {
      return null;
    }
    return { companyId, employeeId };
  }

  /**
   * Runs one access write and adopts its answer as the new overview state.
   *
   * The endpoints answer the reloaded overview (record T75), so the response **is** the new state:
   * the resource is fed it directly instead of triggering a second `GET`. A failure is surfaced with
   * the server's own message when it carries one, because the named refusals here (a blank reason, a
   * self-approval, an open request) are exactly what the operator has to read.
   */
  private runAccessWrite(request: () => Observable<AccessProvisioningOverview>): void {
    this.accessActionError.set(null);
    request()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (overview) => this.accessResource.set(overview),
        error: (error: unknown) =>
          this.accessActionError.set(
            this.serverEnvelope(error) ?? this.accessService.error() ?? httpErrorMessage(error),
          ),
      });
  }

  /** The API envelope's own `message`, or `null` when the failure carried none. */
  private serverEnvelope(error: unknown): string | null {
    const message = (unwrapHttpError(error)?.error as { message?: unknown } | undefined)?.message;
    return typeof message === 'string' && message.trim() !== '' ? message : null;
  }

  /**
   * Opens the scope assign dialog for the selected company and employee (`S1`, `S7`): the one dialog
   * for the four levels, store included.
   *
   * The company is fixed by the employee and shown, not chosen, and the employee's own role names
   * come along as a **hint** for the level — never as a gate. Guarded on the write role like every
   * other write control, so a reader cannot reach a writable surface by a second route.
   */
  onAssignScope(): void {
    const companyId = this.selectedCompanyId();
    const employeeId = this.employeeId();
    if (!this.canWrite || !companyId || !employeeId) {
      return;
    }
    const data: ScopeAssignmentDialogData = {
      companyId,
      companyName: this.selectedCompanyName() ?? companyId,
      employeeId,
      employeeRoles: this.employeeScopeRoles(),
    };
    this.dialog
      .open<ScopeAssignmentDialog, ScopeAssignmentDialogData, ScopeAssignmentDialogResult>(
        ScopeAssignmentDialog,
        { data },
      )
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => {
        // `undefined` is a bare Material dismissal and means the same as `null`: no write.
        if (result?.outcome === 'created') {
          // The retry landed: the partial message of a previous failed activation is stale now (`S9`).
          this.partialAssignmentError.set(null);
          this.scopeAssignmentsResource.reload();
        }
      });
  }

  /**
   * Closes a scope assignment after the operator confirms it in the shared `ConfirmDialog`, then
   * reloads the scope assignments.
   *
   * The confirmation lives here, next to the write, exactly as the contracts one does; the section
   * renders the control and emits the row.
   */
  onCloseScopeAssignment(assignment: ScopeAssignment): void {
    const companyId = this.selectedCompanyId();
    const employeeId = this.employeeId();
    if (!this.canWrite || !companyId || !employeeId) {
      return;
    }
    const levelName =
      assignment.level === 'COUNTRY'
        ? 'país'
        : assignment.level === 'REGION'
          ? 'región'
          : assignment.level === 'ZONE'
            ? 'zona'
            : 'tienda';
    const data: ConfirmDialogData = {
      title: 'Cerrar alcance asignado',
      message: `¿Confirmás que querés cerrar la asignación de alcance "${assignment.nodeName}"? Se conserva en el historial y ese ${levelName} deja de contar para el alcance del empleado.`,
      confirmLabel: 'Cerrar asignación',
      destructive: true,
    };
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, { data })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((confirmed) => {
        if (confirmed) {
          this.closeScopeAssignment(companyId, employeeId, assignment);
        }
      });
  }

  private closeScopeAssignment(
    companyId: string,
    employeeId: string,
    assignment: ScopeAssignment,
  ): void {
    this.scopeAssignmentActionError.set(null);
    this.scopeAssignmentService
      .closeAssignment(companyId, employeeId, assignment.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.scopeAssignmentsResource.reload(),
        // The service maps the status; its message is the one the operator can act on.
        error: () =>
          this.scopeAssignmentActionError.set(
            this.scopeAssignmentService.error() ?? 'Error al cerrar la asignación de alcance',
          ),
      });
  }

  /**
   * Opens the create mode of the contract dialog.
   *
   * Guarded on the write role and on a loaded employee: a reader must not reach a
   * writable surface by a second route, and the dialog needs the employee and the
   * current contracts to resolve the effect-before-saving notice.
   */
  onNewContract(): void {
    const companyId = this.selectedCompanyId();
    const employee = this.employee();
    if (!this.canWrite || !companyId || !employee) {
      return;
    }
    this.openContractDialog('create', companyId, employee);
  }

  /**
   * Opens the close mode of the contract dialog for the current contract.
   *
   * The action is only rendered while `currentContract()` is non-null; this guard
   * is defence in depth, like the create one above.
   */
  onCloseContract(): void {
    const companyId = this.selectedCompanyId();
    const employee = this.employee();
    if (!this.canWrite || !companyId || !employee || !this.currentContract()) {
      return;
    }
    this.openContractDialog('close', companyId, employee);
  }

  private openContractDialog(
    mode: 'create' | 'close',
    companyId: string,
    employee: Employee,
  ): void {
    const data: ContractDialogData = {
      mode,
      companyId,
      employee,
      contracts: this.contracts(),
    };
    this.dialog
      .open<ContractDialog, ContractDialogData, ContractDialogResult>(ContractDialog, { data })
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((result) => this.onContractDialogClosed(result));
  }

  /**
   * Reacts to the dialog's close, reading the **two separate facts** a create act can carry (`D9`).
   *
   * `undefined` is treated as no result on purpose: Material can close the ref
   * itself (Esc or backdrop) and that close carries no result, so it means the same
   * thing as `null` — no write. The employee read is deliberately **not** reloaded:
   * the two reads stay independent (`T52`), and the header's "Puesto actual" is
   * derived from the contracts, which do reload.
   *
   * A `created` result always reloads the contracts. When the act also wrote an assignment, the scope
   * read reloads with it; when the assignment failed, the page reloads that read too — a write can
   * succeed on the server and still fail on the wire — and tells the truth in
   * {@link partialAssignmentError} instead of implying that nothing was saved. A contract-only result
   * (the pre-existing empty-picker act, `D8`) reloads the contracts and nothing else.
   */
  private onContractDialogClosed(result: ContractDialogResult | undefined): void {
    if (result?.outcome === 'closed') {
      this.contractsResource.reload();
      return;
    }
    if (result?.outcome !== 'created') {
      return;
    }

    this.contractsResource.reload();
    this.partialAssignmentError.set(null);
    if (result.assignmentError) {
      this.partialAssignmentError.set(
        `El contrato quedó creado, pero la tienda no se asignó: ${result.assignmentError}. ` +
          'Podés asignarla desde la pestaña «Alcance».',
      );
      this.scopeAssignmentsResource.reload();
      return;
    }
    if (result.assignment) {
      this.scopeAssignmentsResource.reload();
    }
  }

  onBackToList(): void {
    const companyId = this.selectedCompanyId();
    this.router.navigate(['/hr/employees'], {
      queryParams: companyId ? { companyId } : {},
    });
  }
}
