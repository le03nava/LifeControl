import { Component, input, output, computed, ChangeDetectionStrategy } from '@angular/core';
import { CompanyRegion } from '../../models/region.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';

@Component({
  selector: 'app-regions-card',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatCardModule],
  templateUrl: './regions-card.html',
  styleUrl: './regions-card.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RegionsCard {
  region = input<CompanyRegion | undefined>();
  editRegion = output<string>();
  deleteRegion = output<string>();

  readonly isEnabled = computed(() => this.region()?.enabled ?? true);
  readonly statusLabel = computed(() => (this.isEnabled() ? 'Activo' : 'Inactivo'));

  onEdit(event?: Event): void {
    if (event) {
      event.stopPropagation();
    }
    if (this.region()?.id) {
      this.editRegion.emit(this.region()!.id);
    }
  }

  onDelete(event: Event): void {
    event.stopPropagation();
    if (this.region()?.id) {
      this.deleteRegion.emit(this.region()!.id);
    }
  }
}
