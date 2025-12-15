import { inject, Injectable, signal, WritableSignal } from '@angular/core';
import { Product } from '../models/product.models';
import { Observable } from 'rxjs';
import { HttpClient } from '@angular/common/http';

@Injectable({
  providedIn: 'root',
})
export class ProductService {
  private apiUrl = 'http://localhost:9000/api/product';
  private http = inject(HttpClient);
  private productList: WritableSignal<Product[]> = signal([]);

  public products = this.productList.asReadonly();

  state = signal({
    products: new Map<string, Product>(),
  });

  getFormattedProducts() {
    return Array.from(this.state().products.values());
  }

  getProducts(): void {
    this.http.get<Product[]>(this.apiUrl).subscribe((products) => {
      products.forEach((product) => {
        this.state().products.set(product.id!, product);
      });
      this.state.set({ products: this.state().products });
    });
  }

  getProductList(): void {
    this.http.get<Product[]>(`${this.apiUrl}`).subscribe({
      next: (productList) => {
        console.log('Producto lists', productList);
        this.productList.set(productList);
      },
    });
  }

  getProductById(id: string): Observable<Product> {
    return this.http.get<Product>(`${this.apiUrl}/${id}`);
  }

  createProduct(data: Product): Observable<Product> {
    return this.http.post<Product>(`${this.apiUrl}`, data);
  }

  updateProduct(data: Product): Observable<Product> {
    return this.http.put<Product>(`${this.apiUrl}`, data);
  }

  deleteProduct(id: string): Observable<void> {
    return this.http.delete<void>(`${this.apiUrl}/${id}`);
  }
}
