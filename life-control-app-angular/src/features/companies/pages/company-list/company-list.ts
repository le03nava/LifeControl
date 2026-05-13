import { Component, effect, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Button, Modal } from '@shared/ui';
import { CompanyService } from '@features/companies/data/company.service';
import { CompaniesCard } from '@features/companies/components';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule } from '@angular/material/paginator';
import { rxResource } from '@angular/core/rxjs-interop';
import { Company, Page } from '@features/companies/models/company.models';

@Component({
  selector: 'app-company-list',
  imports: [RouterLink, Button, Modal, CompaniesCard, MatIconModule, MatPaginatorModule],
  templateUrl: './company-list.html',
  styleUrls: ['./company-list.scss'],
})
export class CompanyList {
  companyService = inject(CompanyService);
  private router = inject(Router);

  showDeleteModal = signal(false);
  companyToDelete = signal<{ id: string; name: string } | null>(null);
  isDeleting = signal(false);

  // Paginación
  readonly pageSize = signal(12);
  readonly pageIndex = signal(0);

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
  }

  editCompany(id: string): void {
    this.router.navigate([`/companies/edit/${id}`]);
  }

  confirmDelete(companyInfo: { id: string; name: string }): void {
    this.companyToDelete.set({ id: companyInfo.id, name: companyInfo.name });
    this.showDeleteModal.set(true);
  }

  cancelDelete(): void {
    this.showDeleteModal.set(false);
    this.companyToDelete.set(null);
  }

  clearSearch(): void {
    this.searchQuery.set('');
  }

  onPageChange(event: { pageIndex: number; pageSize: number }): void {
    this.pageIndex.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  async executeDelete(): Promise<void> {
    const company = this.companyToDelete();
    if (!company || this.isDeleting()) return;

    this.isDeleting.set(true);
    this.companyService.deleteCompany(company.id).subscribe({
      next: () => {
        this.isDeleting.set(false);
        this.showDeleteModal.set(false);
        this.companiesResource.reload();
      },
      error: () => {
        this.isDeleting.set(false);
      },
    });
  }
}
