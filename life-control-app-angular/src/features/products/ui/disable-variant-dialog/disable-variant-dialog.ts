import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import {
  MatDialogTitle,
  MatDialogContent,
  MatDialogActions,
  MatDialogClose,
  MatDialogRef,
  MAT_DIALOG_DATA,
} from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { ProductVariant } from '../../models/product-variant.models';

/**
 * Confirmation for the soft delete of a variant (`DELETE .../variants/{id}`).
 *
 * The backend disables the row instead of removing it, so the copy never says
 * "eliminar" and states the variant can be re-enabled. The resolved value is the
 * `boolean` the caller reads from `afterClosed()`.
 */
@Component({
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatDialogTitle, MatDialogContent, MatDialogActions, MatDialogClose],
  templateUrl: './disable-variant-dialog.html',
})
export class DisableVariantDialogComponent {
  readonly dialogRef = inject(MatDialogRef<DisableVariantDialogComponent>);
  readonly data: { variant: ProductVariant } = inject(MAT_DIALOG_DATA);
}
