import { ChangeDetectionStrategy, Component, effect, input } from '@angular/core';
import { ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import {
  CreateStoreAreaRequest,
  StoreArea,
  StoreAreaControl,
  UpdateStoreAreaRequest,
} from '../../models/store-area.models';
import { StoreLeafFormBase } from '../store-leaf-form-base';
import { integerValidator } from '../form-errors';

/**
 * Self-contained reactive form for creating and editing a store area.
 * Emits a cleaned request payload; the page owns the API call.
 */
@Component({
  selector: 'app-store-area-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './store-area-form.html',
  styleUrl: './store-area-form.scss',
})
export class StoreAreaForm extends StoreLeafFormBase<
  CreateStoreAreaRequest | UpdateStoreAreaRequest
> {
  // ─── Inputs ─────────────────────────────────────────────────
  readonly area = input<StoreArea | null>(null);

  protected readonly entity = this.area;
  protected readonly formLabel = 'StoreAreaForm';

  // ─── Self-contained FormGroup ───────────────────────────────
  readonly formGroup = this.fb.group<StoreAreaControl>({
    areaCode: this.fb.control('', [Validators.required, Validators.maxLength(10)]),
    areaName: this.fb.control('', [Validators.required, Validators.maxLength(100)]),
    description: this.fb.control<string | null>(null, [Validators.maxLength(255)]),
    displayOrder: this.fb.control<number | null>(null, [Validators.min(0), integerValidator]),
  });

  constructor() {
    super();
    this.bindServerErrors();

    // --- Edit mode: patch the form whenever an area arrives ---
    effect(() => {
      const area = this.area();
      if (!area) return;

      this.formGroup.patchValue({
        areaCode: area.areaCode,
        areaName: area.areaName,
        description: area.description,
        displayOrder: area.displayOrder,
      });
    });
  }

  // ─── Methods ────────────────────────────────────────────────
  protected buildRequest(): CreateStoreAreaRequest | UpdateStoreAreaRequest {
    const raw = this.formGroup.getRawValue();
    const areaCode = raw.areaCode.trim();
    const areaName = raw.areaName.trim();
    const description = raw.description?.trim();
    const displayOrder = raw.displayOrder;

    return {
      areaCode,
      areaName,
      ...(description ? { description } : {}),
      ...(displayOrder !== null && displayOrder !== undefined ? { displayOrder } : {}),
    };
  }
}
