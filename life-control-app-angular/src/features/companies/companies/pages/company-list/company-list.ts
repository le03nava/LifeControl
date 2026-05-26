import { Component, DestroyRef, effect, inject, signal, computed } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { PageHeader } from '@shared/ui';
import { CompanyService } from '@features/companies/companies/data/company.service';
import { CompaniesCard } from '@features/companies/companies/components';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatDialog } from '@angular/material/dialog';
import { DeleteCompanyDialogComponent } from '../../ui/delete-company-dialog/delete-company-dialog';

@Component({
  selector: 'app-company-list',
  imports: [RouterLink, PageHeader, CompaniesCard, MatIconModule, MatPaginatorModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  templateUrl: './company-list.html',
  styleUrl: './company-list.scss',
})
export class CompanyList {
  companyService = inject(CompanyService);
  private router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  // Paginación
  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

  // Responsive: mobile detection via matchMedia
  readonly isMobile = signal(false);
  readonly pageSizeOptions = computed(() => this.isMobile() ? [6, 12] : [6, 12, 24, 48]);

  // Search: el input actualiza searchQuery en cada keystroke (para el template)
  readonly searchQuery = signal('');

  // Search debounced: se usa para la llamada API (300ms después de que el user deja de tipear)
  private readonly _debouncedSearch = signal('');

  // rxResource: llama al backend automáticamente cuando cambian pageIndex, pageSize o _debouncedSearch
  readonly companiesResource = rxResource({
    params: () => ({
      page: this.pageIndex(),
      size: this.pageSize(),
      search: this._debouncedSearch(),
    }),
    stream: ({ params }) =>
      this.companyService.getCompanies(params.page, params.size, params.search || undefined),
  });

  // Computed helpers
  readonly companies = this.companiesResource.value;
  readonly loading = this.companiesResource.isLoading;
  readonly error = this.companiesResource.error;

  constructor() {
    // Debounce effect: cuando searchQuery cambia, espera 300ms y actualiza _debouncedSearch
    effect((onCleanup) => {
      const query = this.searchQuery();
      const timer = setTimeout(() => {
        this._debouncedSearch.set(query);
      }, 300);
      onCleanup(() => clearTimeout(timer));
    });

    // Reset a página 0 cuando cambia la búsqueda debounced
    effect(() => {
      this._debouncedSearch();
      if (this.pageIndex() !== 0) {
        this.pageIndex.set(0);
      }
    });

    // MatchMedia for mobile paginator adaptation
    if (typeof window !== 'undefined') {
      const mql = window.matchMedia('(max-width: 575.98px)');
      this.isMobile.set(mql.matches);
      mql.addEventListener('change', (e) => this.isMobile.set(e.matches));
    }
  }

  editCompany(id: string): void {
    this.router.navigate([`/companies/edit/${id}`]);
  }

  confirmDelete(companyInfo: { id: string; name: string }): void {
    const dialogRef = this.dialog.open(DeleteCompanyDialogComponent, {
      data: { companyName: companyInfo.name },
    });
    dialogRef.afterClosed().subscribe((result: boolean) => {
      if (result) {
        this.companyService.deleteCompany(companyInfo.id).pipe(
          takeUntilDestroyed(this.destroyRef),
        ).subscribe({
          next: () => this.companiesResource.reload(),
        });
      }
    });
  }

  clearSearch(): void {
    this.searchQuery.set('');
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }
}
