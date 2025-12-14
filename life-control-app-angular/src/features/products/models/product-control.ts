import { FormControl } from '@angular/forms';

export interface ProductControl {
  id: FormControl<string>;
  name: FormControl<string>;
  description: FormControl<string>;
  price: FormControl<number>;
}
