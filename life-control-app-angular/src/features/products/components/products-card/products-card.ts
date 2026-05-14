import { Component, input, output } from '@angular/core';
import { Product } from '../../models/product.models';
import { Button } from '@shared/ui';
import { MatIconModule } from '@angular/material/icon';


@Component({
  selector: 'app-products-card',
  standalone: true,
  imports: [Button, MatIconModule],
  templateUrl: './products-card.html',
  styleUrl: './products-card.scss',
})
export class ProductsCard {
  product = input<Product | undefined>();
  editProduct = output<string>();
  deleteProduct = output<{ id: string; name: string }>();

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
}
