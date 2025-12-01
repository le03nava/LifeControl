import { inject, Injectable, signal } from '@angular/core';
import { Product } from '../models/product.models';
import { Observable } from 'rxjs';
import { HttpClient } from '@angular/common/http';

@Injectable({
  providedIn: 'root',
})
export class ProductService {
  private apiUrl = 'http://localhost:9000/api/product';
  private http = inject(HttpClient);

  state = signal({
    products: new Map<string, Product>(),
  });

  getFormattedProducts() {
    return Array.from(this.state().products.values());
  }

  getProducts(): void {
    this.http.get<Product[]>(this.apiUrl).subscribe((products) => {
      products.forEach((product) => {
        this.state().products.set(product.id, product);
      });
      this.state.set({ products: this.state().products });
    });
  }

  getProductById(id: string): Observable<Product> {
    return this.http.get<Product>(`${this.apiUrl}/${id}`);
  }

  createProduct(data: Product): Observable<Product> {
    return this.http.post<Product>(`${this.apiUrl}`, data);
  }

  deleteProduct(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
