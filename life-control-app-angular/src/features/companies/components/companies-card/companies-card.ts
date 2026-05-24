import { Component, input, output, computed } from '@angular/core';
import { Company } from '../../models/company.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';

@Component({
  selector: 'app-companies-card',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatCardModule],
  templateUrl: './companies-card.html',
  styleUrl: './companies-card.scss',
})
export class CompaniesCard {
  company = input<Company | undefined>();
  editCompany = output<string>();
  deleteCompany = output<{ id: string; name: string }>();

  // Computed para status badge
  readonly isActive = computed(() => this.company()?.enabled ?? true);
  readonly statusLabel = computed(() => this.isActive() ? 'Activo' : 'Inactivo');

  onEditCompany(event?: Event): void {
    if (event) {
      event.stopPropagation();
    }
    if (this.company()?.id) {
      this.editCompany.emit(this.company()!.id);
    }
  }

  onDeleteCompany(event: Event): void {
    event.stopPropagation();
    if (this.company()?.id && this.company()?.companyName) {
      this.deleteCompany.emit({ id: this.company()!.id, name: this.company()!.companyName });
    }
  }
}
