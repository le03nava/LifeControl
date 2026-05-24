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
  templateUrl: './delete-company-dialog.html',
})
export class DeleteCompanyDialogComponent {
  readonly dialogRef = inject(MatDialogRef<DeleteCompanyDialogComponent>);
  readonly data: { companyName: string } = inject(MAT_DIALOG_DATA);
}
