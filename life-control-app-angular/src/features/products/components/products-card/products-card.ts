import { Component, input, output } from '@angular/core';
import { Product } from '../../models/product.models';

@Component({
  selector: 'app-products-card',
  standalone: true,
  imports: [],
  templateUrl: './products-card.html',
  styleUrl: './products-card.scss',
})
export class ProductsCard {
  product = input<Product | undefined>();
  editProduct = output<string>();
  deleteProduct = output<{ id: string; name: string }>();
  viewProduct = output<string>();

  onEditProduct(event?: MouseEvent): void {
    if (event) {
      event.stopPropagation();
    }
    if (this.product()?.id) {
      this.editProduct.emit(this.product()!.id);
    }
  }

  onDeleteProduct(event: MouseEvent): void {
    event.stopPropagation();
    if (this.product()?.id && this.product()?.name) {
      this.deleteProduct.emit({ id: this.product()!.id, name: this.product()!.name });
    }
  }

  onViewProduct(event: MouseEvent): void {
    event.stopPropagation();
    if (this.product()?.id) {
      this.viewProduct.emit(this.product()!.id);
    }
  }
}
