import {
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { CompanyZone } from '../../models/zone.models';

@Component({
  selector: 'app-zones-list',
  standalone: true,
  imports: [
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatSlideToggleModule,
  ],
  templateUrl: './zones-list.html',
  styleUrl: './zones-list.scss',
})
export class ZonesList {
  // ─── Inputs ─────────────────────────────────────────────────
  zones = input<CompanyZone[]>([]);
  loading = input<boolean>(false);
  error = input<string | null>(null);

  // ─── Outputs ────────────────────────────────────────────────
  edit = output<string>();
  remove = output<string>();
  enable = output<{ id: string; enable: boolean }>();

  // ─── Internal state ─────────────────────────────────────────
  showDisabled = signal(false);

  // ─── Computed ───────────────────────────────────────────────
  readonly filteredZones = computed(() => {
    const all = this.zones();
    if (this.showDisabled()) return all;
    return all.filter((z) => z.enabled);
  });

  // ─── Table config ───────────────────────────────────────────
  displayedColumns = ['zoneCode', 'zoneName', 'description', 'displayOrder', 'enabled', 'actions'];

  // ─── Methods ────────────────────────────────────────────────
  onEdit(zoneId: string): void {
    this.edit.emit(zoneId);
  }

  onRemove(zoneId: string): void {
    this.remove.emit(zoneId);
  }

  onToggle(zone: CompanyZone): void {
    this.enable.emit({ id: zone.id, enable: !zone.enabled });
  }
}
