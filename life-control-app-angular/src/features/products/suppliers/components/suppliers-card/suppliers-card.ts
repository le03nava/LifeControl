import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { Supplier } from '../../models/supplier.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';

@Component({
  selector: 'app-suppliers-card',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatCardModule],
  templateUrl: './suppliers-card.html',
  styleUrl: './suppliers-card.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SuppliersCard {
  readonly supplier = input.required<Supplier>();
  readonly loading = input<boolean>(false);

  readonly editSupplier = output<string>();
  readonly deleteSupplier = output<{ id: string; name: string }>();

  readonly isActive = computed(() => this.supplier().enabled);
  readonly statusLabel = computed(() => (this.isActive() ? 'Activo' : 'Inactivo'));

  readonly hasAddress = computed(() => {
    const s = this.supplier();
    return !!(s.city || s.state);
  });

  readonly addressDisplay = computed(() => {
    const s = this.supplier();
    const parts: string[] = [];
    if (s.city) parts.push(s.city);
    if (s.state) parts.push(s.state);
    return parts.join(', ');
  });

  onEditSupplier(event?: Event): void {
    if (event) {
      event.stopPropagation();
    }
    this.editSupplier.emit(this.supplier().id);
  }

  onDeleteSupplier(event: Event): void {
    event.stopPropagation();
    const s = this.supplier();
    this.deleteSupplier.emit({ id: s.id, name: s.supplierName });
  }
}
