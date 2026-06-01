import { Component, inject } from '@angular/core';
import {
  MatDialogTitle,
  MatDialogContent,
  MatDialogActions,
  MatDialogClose,
  MatDialogRef,
  MAT_DIALOG_DATA,
} from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';

@Component({
  standalone: true,
  imports: [MatButtonModule, MatDialogTitle, MatDialogContent, MatDialogActions, MatDialogClose],
  templateUrl: './delete-product-dialog.html',
})
export class DeleteProductDialogComponent {
  readonly dialogRef = inject(MatDialogRef<DeleteProductDialogComponent>);
  readonly data: { productName: string } = inject(MAT_DIALOG_DATA);
}
