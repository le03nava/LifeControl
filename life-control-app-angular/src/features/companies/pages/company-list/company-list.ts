import { Component, inject, signal, computed, effect, OnInit } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Button, Modal } from '@shared/ui';
import { CompanyService } from '@features/companies/data/company.service';
import { CompaniesCard } from '@features/companies/components';
import { MatIconModule } from '@angular/material/icon';
import { Company } from '@features/companies/models/company.models';

@Component({
  selector: 'app-company-list',
  imports: [RouterLink, Button, Modal, CompaniesCard, MatIconModule],
  templateUrl: './company-list.html',
  styleUrls: ['./company-list.scss'],
})
export class CompanyList implements OnInit {
  companyService = inject(CompanyService);
  private router = inject(Router);

  showDeleteModal = signal(false);
  companyToDelete = signal<{ id: string; name: string } | null>(null);
  isDeleting = signal(false);

  // Search con debounce
  readonly searchQuery = signal('');
  private readonly debounceDelay = 300; // ms

  // Empresas filtradas (computed reactivo)
  readonly filteredCompanies = computed(() => {
    const query = this.searchQuery().toLowerCase().trim();
    const companies = this.companyService.companies();

    if (!query) {
      return companies;
    }

    return companies.filter((c: Company) =>
      c.companyName?.toLowerCase().includes(query) ||
      c.rfc?.toLowerCase().includes(query) ||
      c.razonSocial?.toLowerCase().includes(query) ||
      c.email?.toLowerCase().includes(query)
    );
  });

  constructor() {
    // Debounce effect - limpia el timeout anterior en cada cambio
    effect((onCleanup) => {
      const query = this.searchQuery();
      const timer = setTimeout(() => {
        // El computed ya se actualiza automáticamente
        // Si necesitaramos server-side search, lo haríamos acá
      }, this.debounceDelay);

      onCleanup(() => clearTimeout(timer));
    });
  }

  ngOnInit(): void {
    this.companyService.getCompanies();
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

  async executeDelete(): Promise<void> {
    const company = this.companyToDelete();
    if (!company || this.isDeleting()) return;

    this.isDeleting.set(true);
    this.companyService.deleteCompany(company.id).subscribe({
      next: () => {
        this.isDeleting.set(false);
        this.showDeleteModal.set(false);
        this.companyService.getCompanies();
      },
      error: () => {
        this.isDeleting.set(false);
      },
    });
  }
}
