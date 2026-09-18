import { ChangeDetectionStrategy, Component, effect, input } from '@angular/core';
import { ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import {
  CreateStoreLocationRequest,
  StoreLocation,
  StoreLocationControl,
  UpdateStoreLocationRequest,
} from '../../models/store-location.models';
import { StoreLeafFormBase } from '../store-leaf-form-base';

/**
 * Self-contained reactive form for creating and editing a store location.
 * Emits a cleaned request payload; the page owns the API call.
 */
@Component({
  selector: 'app-store-location-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './store-location-form.html',
  styleUrl: './store-location-form.scss',
})
export class StoreLocationForm extends StoreLeafFormBase<
  CreateStoreLocationRequest | UpdateStoreLocationRequest
> {
  // ─── Inputs ─────────────────────────────────────────────────
  readonly storeLocation = input<StoreLocation | null>(null);

  protected readonly entity = this.storeLocation;
  protected readonly formLabel = 'StoreLocationForm';

  // ─── Self-contained FormGroup ───────────────────────────────
  readonly formGroup = this.fb.group<StoreLocationControl>({
    locationCode: this.fb.control('', [Validators.required, Validators.maxLength(10)]),
    locationName: this.fb.control('', [Validators.required, Validators.maxLength(100)]),
    description: this.fb.control<string | null>(null, [Validators.maxLength(255)]),
    displayOrder: this.fb.control<number | null>(null, [Validators.min(0)]),
  });

  constructor() {
    super();
    this.bindServerErrors();

    // --- Edit mode: patch the form whenever a store location arrives ---
    effect(() => {
      const storeLocation = this.storeLocation();
      if (!storeLocation) return;

      this.formGroup.patchValue({
        locationCode: storeLocation.locationCode,
        locationName: storeLocation.locationName,
        description: storeLocation.description,
        displayOrder: storeLocation.displayOrder,
      });
    });
  }

  // ─── Methods ────────────────────────────────────────────────
  protected buildRequest(): CreateStoreLocationRequest | UpdateStoreLocationRequest {
    const raw = this.formGroup.getRawValue();
    const locationCode = raw.locationCode.trim();
    const locationName = raw.locationName.trim();
    const description = raw.description?.trim();
    const displayOrder = raw.displayOrder;

    return {
      locationCode,
      locationName,
      ...(description ? { description } : {}),
      ...(displayOrder !== null && displayOrder !== undefined ? { displayOrder } : {}),
    };
  }
}
