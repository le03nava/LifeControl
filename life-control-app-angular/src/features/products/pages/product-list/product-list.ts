import { Component, computed, inject, OnInit, Signal, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Button, Modal } from '@shared/ui';
import { ProductService } from '@features/products/data/product.service';
import { Product } from '@features/products/models/product.models';

@Component({
  selector: 'app-product-list',
  imports: [RouterLink, Button, Modal],
  templateUrl: './product-list.html',
  styleUrls: ['./product-list.scss'],
})
export class ProductList implements OnInit {
  productService = inject(ProductService);

  showDeleteModal = signal(false);
  userToDelete = signal<{ id: string; name: string } | null>(null);
  isDeleting = signal(false);

  products: Signal<Product[] | undefined> = computed(() => {
    return this.productService.getFormattedProducts();
  });

  ngOnInit(): void {
    this.productService.getProducts();
  }

  confirmDelete(userId: string, userName: string): void {
    this.userToDelete.set({ id: userId, name: userName });
    this.showDeleteModal.set(true);
  }

  cancelDelete(): void {
    this.showDeleteModal.set(false);
    this.userToDelete.set(null);
  }

  async executeDelete(): Promise<void> {
    const user = this.userToDelete();
    if (!user || this.isDeleting()) return;

    this.isDeleting.set(true);
  }
}
