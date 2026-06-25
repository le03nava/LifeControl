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
    const a = this.supplier().address;
    return !!(a?.city || a?.state);
  });

  readonly addressDisplay = computed(() => {
    const a = this.supplier().address;
    const parts: string[] = [];
    if (a?.city) parts.push(a.city);
    if (a?.state) parts.push(a.state);
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
