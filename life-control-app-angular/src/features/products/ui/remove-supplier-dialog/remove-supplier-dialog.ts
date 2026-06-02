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
  templateUrl: './remove-supplier-dialog.html',
})
export class RemoveSupplierDialog {
  readonly dialogRef = inject(MatDialogRef<RemoveSupplierDialog>);
  readonly data: { supplierName: string } = inject(MAT_DIALOG_DATA);
}
