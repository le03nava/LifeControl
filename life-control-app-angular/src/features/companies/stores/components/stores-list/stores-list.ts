import {
  Component,
  computed,
  input,
  output,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTableModule } from '@angular/material/table';
import { CompanyStore } from '../../models/store.models';

@Component({
  selector: 'app-stores-list',
  standalone: true,
  imports: [
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatSlideToggleModule,
  ],
  templateUrl: './stores-list.html',
  styleUrl: './stores-list.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StoresList {
  // ─── Inputs ─────────────────────────────────────────────────
  stores = input<CompanyStore[]>([]);
  loading = input<boolean>(false);
  error = input<string | null>(null);

  // ─── Outputs ────────────────────────────────────────────────
  edit = output<string>();
  toggle = output<{ id: string; enable: boolean }>();

  // ─── Internal state ─────────────────────────────────────────
  showDisabled = signal(false);

  // ─── Computed ───────────────────────────────────────────────
  readonly filteredStores = computed(() => {
    const all = this.stores();
    if (this.showDisabled()) return all;
    return all.filter((s) => s.enabled);
  });

  // ─── Table config ───────────────────────────────────────────
  displayedColumns = ['storeName', 'email', 'phoneNumber', 'city', 'state', 'status', 'actions'];

  // ─── Methods ────────────────────────────────────────────────
  onEdit(storeId: string): void {
    this.edit.emit(storeId);
  }

  onToggle(store: CompanyStore): void {
    this.toggle.emit({ id: store.id, enable: !store.enabled });
  }
}
