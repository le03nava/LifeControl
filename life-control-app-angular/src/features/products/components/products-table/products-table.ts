import { Component, input } from '@angular/core';
import { Product } from '../../models/product.models';

@Component({
  selector: 'app-products-table',
  standalone: true,
  imports: [],
  templateUrl: './products-table.html',
  styleUrl: './products-table.scss',
})
export class ProductsTable {
  products = input<Product[]>([]);
}