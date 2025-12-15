import {
  Component,
  Input,
  Output,
  EventEmitter,
  OnInit,
  inject,
  signal,
  input,
} from '@angular/core';
import {
  FormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
  FormControl,
} from '@angular/forms';
import { Product } from '../../models/product.models';
import { ProductControl } from '../../models/product-control';
import { Field } from '@shared/ui';

@Component({
  selector: 'app-products-form',
  standalone: true,
  imports: [ReactiveFormsModule, Field],
  templateUrl: './products-form.html',
  styleUrl: './products-form.scss',
})
export class ProductsForm implements OnInit {
  formGroup = input.required<FormGroup<ProductControl>>();
  // @Input() product: Product | undefined;
  @Output() saveProduct = new EventEmitter<Product>();
  @Output() cancelForm = new EventEmitter<void>();

  //productForm = signal<FormGroup>(this.createForm());
  public edit = signal(false);
  private fb = inject(FormBuilder);
  ngOnInit(): void {
    //this.productForm = this.createForm();
    console.log('formGroup', this.formGroup().get('id')!.value);
    if (this.formGroup().get('id')!.value !== '') {
      this.edit.set(true);
    }
  }
  /*
  private createForm(): FormGroup<ProductControl> {
    return this.fb.group({
      id: new FormControl(this.product?.id || ''),
      name: new FormControl(this.product?.name || '', [
        Validators.required,
        Validators.minLength(3),
      ]),
      description: new FormControl(this.product?.description || ''),
      price: new FormControl(this.product?.price || 0, [Validators.required, Validators.min(0.01)]),
    });
  }*/

  onSave(): void {
    if (this.formGroup().valid) {
      const formData = this.formGroup();
      console.log('formData', formData);

      const productData: Product = {
        id: formData.get('id')!.value,
        name: formData.get('name')!.value,
        description: formData.get('description')!.value,
        price: formData.get('price')!.value,
      };
      this.saveProduct.emit(productData);
    }
  }

  onCancel(): void {
    console.log(',', this.formGroup().valid);
    //this.cancelForm.emit();
  }

  getControl(controlName: keyof ProductControl): FormControl<string | number | null> {
    return this.formGroup().controls[controlName] as FormControl<any | null>;
  }
}
