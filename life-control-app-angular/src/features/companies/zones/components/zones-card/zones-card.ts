import { Component, input, output, computed, ChangeDetectionStrategy } from '@angular/core';
import { CompanyZone } from '../../models/zone.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';

@Component({
  selector: 'app-zones-card',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatCardModule, MatSlideToggleModule],
  templateUrl: './zones-card.html',
  styleUrl: './zones-card.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ZonesCard {
  zone = input<CompanyZone | undefined>();
  companyId = input<string>('');
  countryId = input<string>('');
  regionId = input<string>('');

  edit = output<string>();
  remove = output<string>();
  enable = output<{ id: string; enable: boolean }>();

  readonly isEnabled = computed(() => this.zone()?.enabled ?? true);
  readonly statusLabel = computed(() => (this.isEnabled() ? 'Activo' : 'Inactivo'));

  onEdit(event?: Event): void {
    if (event) {
      event.stopPropagation();
    }
    if (this.zone()?.id) {
      this.edit.emit(this.zone()!.id);
    }
  }

  onRemove(event: Event): void {
    event.stopPropagation();
    if (this.zone()?.id) {
      this.remove.emit(this.zone()!.id);
    }
  }

  onToggle(): void {
    const z = this.zone();
    if (z?.id) {
      this.enable.emit({ id: z.id, enable: !z.enabled });
    }
  }
}
