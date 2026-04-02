import { Component, inject, signal, OnInit } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Button, Modal } from '@shared/ui';
import { CompanyService } from '@features/companies/data/company.service';
import { CompaniesCard } from '@features/companies/components';

@Component({
  selector: 'app-company-list',
  imports: [RouterLink, Button, Modal, CompaniesCard],
  templateUrl: './company-list.html',
  styleUrls: ['./company-list.scss'],
})
export class CompanyList implements OnInit {
  companyService = inject(CompanyService);
  private router = inject(Router);

  showDeleteModal = signal(false);
  companyToDelete = signal<{ id: string; name: string } | null>(null);
  isDeleting = signal(false);

  // Usar el signal directo del servicio
  companies = this.companyService.companies;

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
