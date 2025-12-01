import { FormControl } from '@angular/forms';

export interface ProductForm {
  id: FormControl<string>;
  name: FormControl<string>;
  description: FormControl<string>;
  price: FormControl<number>;
}
