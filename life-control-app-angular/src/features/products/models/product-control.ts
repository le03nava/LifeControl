import { FormControl } from '@angular/forms';

export interface ProductControl {
  id: FormControl<string | null>;
  name: FormControl<string>;
  description: FormControl<string>;
  price: FormControl<number>;
}
