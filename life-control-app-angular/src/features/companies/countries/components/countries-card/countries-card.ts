import { Component, input, output, ChangeDetectionStrategy } from '@angular/core';
import { CompanyCountry } from '../../models/country.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';

@Component({
  selector: 'app-countries-card',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatCardModule],
  templateUrl: './countries-card.html',
  styleUrl: './countries-card.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CountriesCard {
  cc = input<CompanyCountry | undefined>();
  editCountry = output<string>();
  deleteCountry = output<string>();

  onEdit(event?: Event): void {
    if (event) {
      event.stopPropagation();
    }
    if (this.cc()?.id) {
      this.editCountry.emit(this.cc()!.id);
    }
  }

  onDelete(event: Event): void {
    event.stopPropagation();
    if (this.cc()?.id) {
      this.deleteCountry.emit(this.cc()!.id);
    }
  }
}
