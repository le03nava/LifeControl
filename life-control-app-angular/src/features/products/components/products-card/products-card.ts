import { Component, computed, input, output } from '@angular/core';
import { Product } from '../../models/product.models';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';

@Component({
  selector: 'app-products-card',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatCardModule, MatChipsModule],
  templateUrl: './products-card.html',
  styleUrl: './products-card.scss',
})
export class ProductsCard {
  readonly product = input.required<Product>();
  readonly editProduct = output<Product>();
  readonly deleteProduct = output<Product>();

  readonly isActive = computed(() => this.product().enabled);
  readonly statusLabel = computed(() => (this.isActive() ? 'Activo' : 'Inactivo'));

  onEditProduct(event?: Event): void {
    if (event) {
      event.stopPropagation();
    }
    this.editProduct.emit(this.product());
  }

  onDeleteProduct(event: Event): void {
    event.stopPropagation();
    this.deleteProduct.emit(this.product());
  }
}
