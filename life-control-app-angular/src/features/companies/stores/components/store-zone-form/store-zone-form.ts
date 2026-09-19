import { ChangeDetectionStrategy, Component, effect, input } from '@angular/core';
import { ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import {
  CreateStoreZoneRequest,
  StoreZone,
  StoreZoneControl,
  UpdateStoreZoneRequest,
} from '../../models/store-zone.models';
import { StoreLeafFormBase } from '../store-leaf-form-base';
import { integerValidator } from '../form-errors';

/**
 * Self-contained reactive form for creating and editing a store zone.
 * Emits a cleaned request payload; the page owns the API call.
 */
@Component({
  selector: 'app-store-zone-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './store-zone-form.html',
  styleUrl: './store-zone-form.scss',
})
export class StoreZoneForm extends StoreLeafFormBase<
  CreateStoreZoneRequest | UpdateStoreZoneRequest
> {
  // ─── Inputs ─────────────────────────────────────────────────
  readonly storeZone = input<StoreZone | null>(null);

  protected readonly entity = this.storeZone;
  protected readonly formLabel = 'StoreZoneForm';

  // ─── Self-contained FormGroup ───────────────────────────────
  readonly formGroup = this.fb.group<StoreZoneControl>({
    zoneCode: this.fb.control('', [Validators.required, Validators.maxLength(10)]),
    zoneName: this.fb.control('', [Validators.required, Validators.maxLength(100)]),
    description: this.fb.control<string | null>(null, [Validators.maxLength(255)]),
    displayOrder: this.fb.control<number | null>(null, [Validators.min(0), integerValidator]),
  });

  constructor() {
    super();
    this.bindServerErrors();

    // --- Edit mode: patch the form whenever a store zone arrives ---
    effect(() => {
      const storeZone = this.storeZone();
      if (!storeZone) return;

      this.formGroup.patchValue({
        zoneCode: storeZone.zoneCode,
        zoneName: storeZone.zoneName,
        description: storeZone.description,
        displayOrder: storeZone.displayOrder,
      });
    });
  }

  // ─── Methods ────────────────────────────────────────────────
  protected buildRequest(): CreateStoreZoneRequest | UpdateStoreZoneRequest {
    const raw = this.formGroup.getRawValue();
    const zoneCode = raw.zoneCode.trim();
    const zoneName = raw.zoneName.trim();
    const description = raw.description?.trim();
    const displayOrder = raw.displayOrder;

    return {
      zoneCode,
      zoneName,
      ...(description ? { description } : {}),
      ...(displayOrder !== null && displayOrder !== undefined ? { displayOrder } : {}),
    };
  }
}
