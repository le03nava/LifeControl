import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ProductService } from '@features/products/data/product.service';
import { Product } from '@features/products/models/product.models';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormGroup, Validators, ReactiveFormsModule } from '@angular/forms';
import { FormProduct } from '@features/products/components';

@Component({
  selector: 'app-product-edit',
  imports: [ReactiveFormsModule, FormProduct],
  templateUrl: './product-edit.html',
  styleUrl: './product-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductEdit {
  private route = inject(ActivatedRoute);
  private productService = inject(ProductService);
  private formBuilder = inject(FormBuilder);
  private router = inject(Router);

  productForm = signal<FormGroup>(this.createForm());
  productId = signal(this.route.snapshot.paramMap.get('id'));
  product = toSignal(this.productService.getProductById(this.productId() ?? ''), {
    initialValue: { id: '', name: '', description: '' } as Product,
  });

  private createForm(): FormGroup {
    return this.formBuilder.group({
      // Campo 1: Nombre (requerido)
      nombre: ['', [Validators.required, Validators.minLength(3)]],

      // Campo 2: Descripción (opcional)
      descripcion: [''],

      // Campo 3: Precio (requerido, debe ser un número positivo)
      precio: ['', [Validators.required, Validators.min(0.01), Validators.pattern(/^\d*\.?\d+$/)]],
    });
  }

  onSubmit(): void {
    const form = this.productForm(); // Obtenemos el valor del Signal

    if (form.valid) {
      console.log('✅ Formulario de Producto Válido (Signal/OnPush). Datos:', form.value);

      // La llamada a `reset()` en el FormGroup nativo notifica a Angular
      // y funciona correctamente con OnPush.
      form.reset();
    } else {
      console.log('❌ Formulario Inválido.');
      form.markAllAsTouched();
    }
  }
}
