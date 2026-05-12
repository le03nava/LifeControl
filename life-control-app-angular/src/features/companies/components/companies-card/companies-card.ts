import { Component, input, output } from '@angular/core';
import { Company } from '../../models/company.models';
import { Button } from '@shared/ui';

@Component({
  selector: 'app-companies-card',
  standalone: true,
  imports: [Button],
  templateUrl: './companies-card.html',
  styleUrl: './companies-card.scss',
})
export class CompaniesCard {
  company = input<Company | undefined>();
  editCompany = output<string>();
  deleteCompany = output<{ id: string; name: string }>();
  viewCompany = output<string>();

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

  onViewCompany(event: Event): void {
    event.stopPropagation();
    if (this.company()?.id) {
      this.viewCompany.emit(this.company()!.id);
    }
  }
}
