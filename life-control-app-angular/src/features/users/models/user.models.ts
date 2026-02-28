import { FormControl } from '@angular/forms';

export interface User {
  id: string;
  username: string;
  email: string;
  password?: string;
  name: string;
  lastname: string;
  phone: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface UserControl {
  id: FormControl<string>;
  username: FormControl<string>;
  email: FormControl<string>;
  password: FormControl<string>;
  name: FormControl<string>;
  lastname: FormControl<string>;
  phone: FormControl<string>;
  enabled: FormControl<boolean>;
}
