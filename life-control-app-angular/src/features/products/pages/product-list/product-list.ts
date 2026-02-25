import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Button, Modal } from '@shared/ui';
import { ProductService } from '@features/products/data/product.service';
import { ProductsCard } from '@features/products/components';

@Component({
  selector: 'app-product-list',
  imports: [RouterLink, Button, Modal, ProductsCard],
  templateUrl: './product-list.html',
  styleUrls: ['./product-list.scss'],
})
export class ProductList {
  productService = inject(ProductService);
  private router = inject(Router);

  showDeleteModal = signal(false);
  productToDelete = signal<{ id: string; name: string } | null>(null);
  isDeleting = signal(false);

  // Usar el signal directo del servicio
  products = this.productService.products;

  editProduct(id: string): void {
    this.router.navigate([`/products/edit/${id}`]);
  }

  confirmDelete(productInfo: { id: string; name: string }): void {
    this.productToDelete.set({ id: productInfo.id, name: productInfo.name });
    this.showDeleteModal.set(true);
  }

  cancelDelete(): void {
    this.showDeleteModal.set(false);
    this.productToDelete.set(null);
  }

  async executeDelete(): Promise<void> {
    const product = this.productToDelete();
    if (!product || this.isDeleting()) return;

    this.isDeleting.set(true);
    this.productService.deleteProduct(product.id).subscribe({
      next: () => {
        this.isDeleting.set(false);
        this.showDeleteModal.set(false);
        this.productService.getProductList();
      },
      error: () => {
        this.isDeleting.set(false);
      },
    });
  }
}
