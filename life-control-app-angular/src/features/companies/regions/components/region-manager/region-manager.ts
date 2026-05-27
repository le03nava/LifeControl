import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { CompanyRegion, CompanyRegionRequest } from '../../models/region.models';

const REGION_CODE_PATTERN = /^[a-zA-Z0-9-]+$/;

@Component({
  selector: 'app-region-manager',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatTableModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
  ],
  templateUrl: './region-manager.html',
  styleUrl: './region-manager.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegionManager {
  // ─── Inputs ─────────────────────────────────────────────────
  regions = input.required<CompanyRegion[]>();
  companyId = input.required<string>();
  countryId = input.required<string>();
  loading = input(false);
  errorMessage = input<string | null>(null);

  // ─── Outputs ────────────────────────────────────────────────
  addRegion = output<CompanyRegionRequest>();
  updateRegion = output<{ id: string; data: CompanyRegionRequest }>();
  removeRegion = output<string>();
  enableRegion = output<string>();

  // ─── Table config ───────────────────────────────────────────
  displayedColumns = ['regionCode', 'regionName', 'enabled', 'actions'];

  // ─── Internal state ─────────────────────────────────────────
  editMode = signal<Record<string, boolean>>({});
  showDisabled = signal(false);

  /** Add form — Reactive Form with validators */
  newRegionForm = new FormGroup({
    regionCode: new FormControl('', {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.maxLength(10),
        Validators.pattern(REGION_CODE_PATTERN),
      ],
    }),
    regionName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)],
    }),
  });

  /** Inline edit — one form per row keyed by region id */
  editForms: Record<string, FormGroup<{
    regionCode: FormControl<string>;
    regionName: FormControl<string>;
  }>> = {};

  // ─── Computed ───────────────────────────────────────────────
  filteredRegions = computed(() => {
    const all = this.regions();
    if (this.showDisabled()) return all;
    return all.filter(r => r.enabled);
  });

  // ─── Methods ────────────────────────────────────────────────
  onAdd(): void {
    if (this.newRegionForm.invalid) return;
    const { regionCode, regionName } = this.newRegionForm.getRawValue();
    this.addRegion.emit({ regionCode: regionCode.trim(), regionName: regionName.trim() });
    this.newRegionForm.reset();
  }

  onStartEdit(region: CompanyRegion): void {
    this.editForms[region.id] = new FormGroup({
      regionCode: new FormControl(region.regionCode, {
        nonNullable: true,
        validators: [
          Validators.required,
          Validators.maxLength(10),
          Validators.pattern(REGION_CODE_PATTERN),
        ],
      }),
      regionName: new FormControl(region.regionName, {
        nonNullable: true,
        validators: [Validators.required, Validators.maxLength(100)],
      }),
    });
    this.editMode.update(m => ({ ...m, [region.id]: true }));
  }

  onCancelEdit(id: string): void {
    delete this.editForms[id];
    this.editMode.update(m => {
      const next = { ...m };
      delete next[id];
      return next;
    });
  }

  onSaveEdit(id: string): void {
    const form = this.editForms[id];
    if (!form || form.invalid) return;
    const { regionCode, regionName } = form.getRawValue();
    this.updateRegion.emit({ id, data: { regionCode: regionCode.trim(), regionName: regionName.trim() } });
    delete this.editForms[id];
    this.editMode.update(m => {
      const next = { ...m };
      delete next[id];
      return next;
    });
  }

  onRemove(id: string): void {
    this.removeRegion.emit(id);
  }

  /**
   * When a user slides the toggle:
   * - ON → OFF → soft-delete (disable)
   * - OFF → ON → re-enable
   */
  onToggleRegion(region: CompanyRegion): void {
    if (region.enabled) {
      this.removeRegion.emit(region.id);
    } else {
      this.enableRegion.emit(region.id);
    }
  }
}
