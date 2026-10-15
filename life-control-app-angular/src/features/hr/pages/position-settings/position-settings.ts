import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import {
  AbstractControl,
  FormArray,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { forkJoin } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { ErrorBanner } from '@shared/ui';
import { NotificationService } from '@shared/data/notification';
import { httpErrorMessage } from '@shared/data';
import { hasAnyClientRole, POSITION_WRITE_ROLES } from '@core/security/roles';
import { PositionService } from '../../data/position.service';
import { SeniorityLevelService } from '../../data/seniority-level.service';
import {
  Position,
  PositionRole,
  PositionRolesRequest,
  PositionSalaryBand,
  PositionSalaryBandControl,
  PositionSalaryBandsRequest,
} from '../../models/position.models';
import { SeniorityLevel } from '../../models/seniority-level.models';

/** One editable row of the Tabuladores grid: a global level and its stored band. */
export interface SalaryBandRow {
  seniorityLevelId: string;
  levelLabel: string;
  rank: number;
  /** The stored band this row came from, or `null` when the level has none. */
  stored: PositionSalaryBand | null;
}

/** One checkbox row of the Roles section: a grantable role and its stored state. */
export interface PositionRoleRow {
  roleName: string;
  /** The stored template row, or `null` when the role was never configured. */
  stored: PositionRole | null;
}

/**
 * Row rule of the band editor, on the group of one level.
 *
 * A row with **both** amounts empty is the legal "this level has no band" state
 * — and the only way a previously stored band is cleared, because the API turns
 * an omitted key into `enabled = false` and keeps the row (T10/D13). A row with
 * exactly one amount is refused here instead of being silently dropped from the
 * request, and the range mirrors the API's own check so the client never sends a
 * request it can already prove is a 400.
 */
export function bandRowValidator(group: AbstractControl): ValidationErrors | null {
  const minimum = group.get('minimumSalary')?.value as number | null;
  const maximum = group.get('maximumSalary')?.value as number | null;

  if (minimum === null && maximum === null) {
    return null;
  }
  if (minimum === null || maximum === null) {
    return { incompleteBand: true };
  }
  if (minimum < 0) {
    return { negativeAmount: true };
  }
  if (maximum < minimum) {
    return { invertedBand: true };
  }
  return null;
}

/**
 * `PositionSettings` — the position's policy screen (record T9, W3).
 *
 * One page with **two independent sections**, each with its own read, its own save
 * and its own endpoint: **Tabuladores**, the salary-band editor over the API's
 * full-set save (T10/D13), and **Roles del sistema**, the role-template editor over
 * the sibling contract (T20). The split is deliberate — a failure or a slow read on
 * one side never blanks the other — and it is what makes the two chained slices
 * (D18) reviewable one at a time.
 *
 * The Roles section draws its candidate set from the **backend's allowlist** read
 * (D19) rather than a copy of the fifteen names, so the checkboxes cannot offer what
 * the save would refuse, and it states in the UI what **D6** already decided: the
 * template is a provisioning **seed**, so changing it does not alter anyone's
 * current access.
 *
 * The company is **T21's mold** — the `companyId` query parameter, not the
 * `company_id` claim, which nothing in this repository provisions — and the page
 * takes it from the list's `Configuración` action, which is where D16 put the row
 * action this screen is the destination of.
 *
 * The band grid is **one row per global seniority level**, joined from the
 * catalog and the position's stored bands. The read hands over disabled rows on
 * purpose (D13): a level the previous save omitted is a stored row with
 * `enabled = false`, so the editor edits the rows it is given instead of
 * rebuilding the set from the catalog — which would resurrect the omitted level
 * on the next save. For the same reason a **disabled** stored row is not
 * prefilled: its old amounts are offered as placeholders, and only the operator
 * typing them back turns the level on again.
 */
@Component({
  selector: 'app-position-settings',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    ErrorBanner,
  ],
  templateUrl: './position-settings.html',
  styleUrl: './position-settings.scss',
})
export class PositionSettings implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly positionService = inject(PositionService);
  private readonly seniorityLevelService = inject(SeniorityLevelService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  /** `lc-position` and `lc-admin` reach the save; everyone else reads. */
  readonly canWrite = hasAnyClientRole(POSITION_WRITE_ROLES);

  readonly positionId = signal<string | null>(this.route.snapshot.paramMap.get('id'));
  readonly selectedCompanyId = signal<string | null>(
    this.route.snapshot.queryParamMap.get('companyId'),
  );

  readonly position = signal<Position | null>(null);
  readonly levels = signal<SeniorityLevel[]>([]);
  readonly rows = signal<SalaryBandRow[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly submitError = signal<string | null>(null);

  /**
   * The **Roles del sistema** section, which is its own read, its own save and its
   * own endpoint (T9): a failure on either side never blanks the other one.
   */
  readonly roleRows = signal<PositionRoleRow[]>([]);
  readonly grantableRoles = signal<string[]>([]);
  readonly rolesLoading = signal(false);
  readonly savingRoles = signal(false);
  readonly rolesError = signal<string | null>(null);

  readonly bandsForm = new FormArray<FormGroup<PositionSalaryBandControl>>([]);

  /**
   * The form the Tabuladores section binds: a `FormArray` cannot be bound with
   * `formGroup`, so the rows live inside this one-field group and are addressed in
   * the template through `formArrayName="bands"`.
   */
  readonly bandsFormGroup = new FormGroup({ bands: this.bandsForm });

  /** One `boolean` control per grantable role: checked means the template grants it. */
  readonly rolesForm = new FormArray<FormControl<boolean>>([]);

  /** The form the Roles section binds, with the same one-field wrapper as the bands. */
  readonly rolesFormGroup = new FormGroup({ roles: this.rolesForm });

  ngOnInit(): void {
    const id = this.positionId();
    if (!id) {
      this.loadError.set('No se indicó el puesto a configurar.');
      return;
    }

    const companyId = this.selectedCompanyId();
    if (!companyId) {
      // The nested URL needs the company for every read; without it the screen
      // can only say why nothing loaded instead of issuing a request that would
      // 404 on the way out.
      this.loadError.set('No se indicó la empresa del puesto.');
      this.rolesError.set('No se indicó la empresa del puesto.');
      return;
    }

    // Two independent reads for two independent sections (T9): a failure on one
    // side never blanks the other one's screen.
    this.loadBands(companyId, id);
    this.loadRoles(companyId, id);
  }

  private loadBands(companyId: string, id: string): void {
    this.loading.set(true);
    forkJoin({
      position: this.positionService.getPosition(companyId, id),
      bands: this.positionService.getSalaryBands(companyId, id),
      levels: this.seniorityLevelService.getSeniorityLevels(true),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ position, bands, levels }) => {
          this.loading.set(false);
          this.position.set(position);
          this.levels.set(levels);
          this.seedRows(bands, levels);
        },
        error: (err: HttpErrorResponse) => {
          this.loading.set(false);
          // **Named, never degraded**: an empty grid would read as "this position
          // has no bands configured", which is a different and false statement.
          this.loadError.set(httpErrorMessage(err));
        },
      });
  }

  private loadRoles(companyId: string, id: string): void {
    this.rolesLoading.set(true);
    forkJoin({
      roles: this.positionService.getPositionRoles(companyId, id),
      grantable: this.positionService.getGrantablePositionRoles(companyId, id),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ roles, grantable }) => {
          this.rolesLoading.set(false);
          this.grantableRoles.set(grantable.roles);
          this.seedRoleRows(roles, grantable.roles);
        },
        error: (err: HttpErrorResponse) => {
          this.rolesLoading.set(false);
          // Named for the same reason the bands are: an empty checkbox list would
          // read as "this template grants nothing" instead of "that read failed".
          this.rolesError.set(httpErrorMessage(err));
        },
      });
  }

  /**
   * Rebuilds the grid: one row per global level, in rank order, joined with the
   * stored bands the read handed over.
   *
   * A stored band whose level is **not** in the catalog read makes the screen
   * refuse to render an editable set, because saving the grid without that row
   * would disable it through the omission rule without anyone asking.
   */
  private seedRows(bands: PositionSalaryBand[], levels: SeniorityLevel[]): void {
    const known = new Set(levels.map((level) => level.id));
    if (bands.some((band) => !known.has(band.seniorityLevelId))) {
      this.rows.set([]);
      this.bandsForm.clear();
      this.loadError.set(
        'No se pudo cargar la configuración completa del puesto. Volvé a intentar antes de guardar.',
      );
      return;
    }

    const storedByLevel = new Map(bands.map((band) => [band.seniorityLevelId, band]));
    const rows: SalaryBandRow[] = [...levels]
      .sort((a, b) => a.rank - b.rank)
      .map((level) => ({
        seniorityLevelId: level.id,
        levelLabel: `${level.levelCode} — ${level.levelName}`,
        rank: level.rank,
        stored: storedByLevel.get(level.id) ?? null,
      }));

    const sameShape =
      rows.length === this.rows().length &&
      rows.every((row, index) => row.seniorityLevelId === this.rows()[index].seniorityLevelId);

    this.rows.set(rows);

    if (!sameShape) {
      this.bandsForm.clear();
      for (const row of rows) {
        this.bandsForm.push(this.buildRowGroup(row));
      }
      return;
    }

    // The level set did not change, so the rows are **patched** instead of
    // rebuilt: a rebuilt array reuses the same row elements, and a reused
    // control writes its value to the accessor only once — which would leave the
    // pre-save amounts on screen even when the server changed them. `setValue`
    // notifies the accessor, so the grid follows the response it just received.
    rows.forEach((row, index) => {
      this.bandsForm.at(index).setValue(this.amountsFor(row));
    });
  }

  /** The controls' values for one row: a disabled stored band is never prefilled (D13). */
  private amountsFor(row: SalaryBandRow): {
    minimumSalary: number | null;
    maximumSalary: number | null;
  } {
    if (row.stored?.enabled !== true) {
      return { minimumSalary: null, maximumSalary: null };
    }
    return {
      minimumSalary: row.stored.minimumSalary,
      maximumSalary: row.stored.maximumSalary,
    };
  }

  /**
   * Builds one row's controls.
   *
   * A **disabled** stored row is not prefilled: the read hands it over so the next
   * save cannot resurrect the level a previous save omitted (D13), and prefilling
   * it would do exactly that. Its old amounts travel as placeholders instead.
   */
  private buildRowGroup(row: SalaryBandRow): FormGroup<PositionSalaryBandControl> {
    return new FormGroup<PositionSalaryBandControl>(
      {
        minimumSalary: new FormControl<number | null>(this.amountsFor(row).minimumSalary),
        maximumSalary: new FormControl<number | null>(this.amountsFor(row).maximumSalary),
      },
      { validators: [bandRowValidator] },
    );
  }

  onSave(): void {
    this.submitError.set(null);
    if (!this.canWrite) {
      return;
    }

    const companyId = this.selectedCompanyId();
    const id = this.positionId();
    if (!companyId || !id) {
      return;
    }

    if (this.bandsForm.invalid) {
      this.bandsForm.markAllAsTouched();
      return;
    }

    this.saving.set(true);
    this.positionService
      .saveSalaryBands(companyId, id, this.buildRequest())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (bands) => {
          this.saving.set(false);
          // The response is the stored set **after** the save, which is what says
          // which levels ended up enabled; the grid follows it instead of the
          // request it sent.
          this.seedRows(bands, this.levels());
          this.notifications.showSuccess('Tabuladores guardados correctamente.');
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.submitError.set(httpErrorMessage(err));
        },
      });
  }

  /**
   * The full set the API replaces the position's bands with.
   *
   * A row with both amounts is submitted; a row with none is omitted, which is
   * how the API is told to disable a level it still stores. No `enabled` field is
   * sent, because the wire has none (T10/D13).
   */
  private buildRequest(): PositionSalaryBandsRequest {
    const rows = this.rows();
    const bands = this.bandsForm.controls
      .map((group, index) => ({ group, row: rows[index] }))
      .filter(
        ({ group }) =>
          group.controls.minimumSalary.value !== null ||
          group.controls.maximumSalary.value !== null,
      )
      .map(({ group, row }) => ({
        seniorityLevelId: row.seniorityLevelId,
        minimumSalary: group.controls.minimumSalary.value as number,
        maximumSalary: group.controls.maximumSalary.value as number,
      }));

    return { bands };
  }

  /**
   * Rebuilds the Roles section: one checkbox per grantable role, checked exactly
   * when the stored template grants it.
   *
   * A stored **enabled** role the allowlist no longer carries makes the section
   * refuse to render an editable list, because saving without that role would
   * disable it through the omission rule without anyone asking.
   */
  private seedRoleRows(roles: PositionRole[], grantable: string[]): void {
    const storedByName = new Map(roles.map((role) => [role.roleName, role]));
    if (roles.some((role) => role.enabled && !grantable.includes(role.roleName))) {
      this.roleRows.set([]);
      this.rolesForm.clear();
      this.rolesError.set(
        'La plantilla guardada tiene un rol que ya no está en la lista permitida. Volvé a intentar antes de guardar.',
      );
      return;
    }

    const rows: PositionRoleRow[] = grantable.map((roleName) => ({
      roleName,
      stored: storedByName.get(roleName) ?? null,
    }));
    const sameShape =
      rows.length === this.roleRows().length &&
      rows.every((row, index) => row.roleName === this.roleRows()[index].roleName);
    const checked = rows.map((row) => row.stored?.enabled === true);

    this.roleRows.set(rows);

    if (!sameShape) {
      this.rolesForm.clear();
      for (const value of checked) {
        this.rolesForm.push(new FormControl<boolean>(value, { nonNullable: true }));
      }
      this.applyRoleWriteGate();
      return;
    }

    // Same patch-not-rebuild rule as the band grid: a rebuilt checkbox keeps the
    // DOM state it had, so the response would not be reflected on screen.
    checked.forEach((value, index) => this.rolesForm.at(index).setValue(value));
    this.applyRoleWriteGate();
  }

  /**
   * Disables the role controls for a caller without the write role.
   *
   * The **control** is the authority on its own disabled state: a `[disabled]`
   * binding next to `formControlName` is ignored by a reactive directive (Angular
   * warns about it), and the checkbox follows the control through
   * `setDisabledState`. The values stay readable, so the gate hides the action and
   * never the information.
   */
  private applyRoleWriteGate(): void {
    if (this.canWrite) {
      this.rolesForm.enable({ emitEvent: false });
    } else {
      this.rolesForm.disable({ emitEvent: false });
    }
  }

  /**
   * Saves the role template as a **full set** (T20): every checked name travels and
   * every stored name the request omits is disabled and **kept** by the API. No
   * `enabled` field is sent, because the wire has none.
   */
  onSaveRoles(): void {
    this.rolesError.set(null);
    if (!this.canWrite) {
      return;
    }

    const companyId = this.selectedCompanyId();
    const id = this.positionId();
    if (!companyId || !id) {
      return;
    }

    const rows = this.roleRows();
    const request: PositionRolesRequest = {
      roles: this.rolesForm.controls
        .map((control, index) => ({ checked: control.value, roleName: rows[index].roleName }))
        .filter(({ checked }) => checked)
        .map(({ roleName }) => ({ roleName })),
    };

    this.savingRoles.set(true);
    this.positionService
      .savePositionRoles(companyId, id, request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (roles) => {
          this.savingRoles.set(false);
          // The response is the stored template after the save, which is what says
          // which roles stayed enabled.
          this.seedRoleRows(roles, this.grantableRoles());
          this.notifications.showSuccess('Roles del sistema guardados correctamente.');
        },
        error: (err: HttpErrorResponse) => {
          this.savingRoles.set(false);
          this.rolesError.set(httpErrorMessage(err));
        },
      });
  }

  onBack(): void {
    const companyId = this.selectedCompanyId();
    this.router.navigate(['/hr/positions'], {
      queryParams: companyId ? { companyId } : {},
    });
  }

  /** The message of the row's own rule, or `null` when the row is fine. */
  rowError(index: number): string | null {
    const errors = this.bandsForm.at(index)?.errors;
    if (!errors) {
      return null;
    }
    if (errors['incompleteBand']) {
      return 'Completá el mínimo y el máximo, o dejá los dos vacíos para que el nivel no tenga tabulador.';
    }
    if (errors['negativeAmount']) {
      return 'Los importes no pueden ser negativos.';
    }
    if (errors['invertedBand']) {
      return 'El máximo no puede ser menor que el mínimo.';
    }
    return 'Fila inválida.';
  }

  /** A disabled stored row shows its old amounts as placeholders, never as values. */
  storedPlaceholder(row: SalaryBandRow, field: 'minimumSalary' | 'maximumSalary'): string {
    if (!row.stored || row.stored.enabled) {
      return '';
    }
    return String(row.stored[field]);
  }
}
