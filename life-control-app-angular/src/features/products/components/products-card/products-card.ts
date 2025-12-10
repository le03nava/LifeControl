import { Component, Input, Output, EventEmitter } from '@angular/core';
import { Product } from '../../models/product.models';

@Component({
  selector: 'app-products-card',
  standalone: true,
  imports: [],
  templateUrl: './products-card.html',
  styleUrl: './products-card.scss',
})
export class ProductsCard {
  @Input() product: Product | undefined;
  @Output() editProduct = new EventEmitter<string>();
  @Output() deleteProduct = new EventEmitter<{ id: string; name: string }>();
  @Output() viewProduct = new EventEmitter<string>();

  onEditProduct(event?: MouseEvent): void {
    if (event) {
      event.stopPropagation();
    }
    if (this.product?.id) {
      this.editProduct.emit(this.product.id);
    }
  }

  onDeleteProduct(event: MouseEvent): void {
    event.stopPropagation();
    if (this.product?.id && this.product?.name) {
      this.deleteProduct.emit({ id: this.product.id, name: this.product.name });
    }
  }

  onViewProduct(event: MouseEvent): void {
    event.stopPropagation();
    if (this.product?.id) {
      this.viewProduct.emit(this.product.id);
    }
  }
}