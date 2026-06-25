import { Component, input, output, computed, ChangeDetectionStrategy } from '@angular/core';
import { CompanyStore } from '../../models/store.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';

@Component({
  selector: 'app-stores-card',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatCardModule, MatSlideToggleModule],
  templateUrl: './stores-card.html',
  styleUrl: './stores-card.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StoresCard {
  store = input<CompanyStore | undefined>();

  edit = output<string>();
  toggle = output<string>();

  readonly isEnabled = computed(() => this.store()?.enabled ?? true);
  readonly statusLabel = computed(() => (this.isEnabled() ? 'Activo' : 'Inactivo'));

  readonly addressSummary = computed(() => {
    const s = this.store();
    if (!s?.address) return '';
    const addr = s.address;
    const parts: string[] = [];
    if (addr.street) {
      parts.push(addr.streetNumber ? `${addr.street} ${addr.streetNumber}` : addr.street);
    }
    if (addr.city) {
      parts.push(addr.state ? `${addr.city}, ${addr.state}` : addr.city);
    }
    return parts.join(' — ');
  });

  onEdit(event?: Event): void {
    if (event) {
      event.stopPropagation();
    }
    if (this.store()?.id) {
      this.edit.emit(this.store()!.id);
    }
  }

  onToggle(): void {
    const s = this.store();
    if (s?.id) {
      this.toggle.emit(s.id);
    }
  }
}
