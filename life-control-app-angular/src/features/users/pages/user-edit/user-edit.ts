import { ChangeDetectionStrategy, Component, inject, signal, effect } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { UserService } from '@features/users/data/user.service';
import { User, UserControl } from '@features/users/models/user.models';
import {
  NonNullableFormBuilder,
  FormGroup,
  Validators,
  ReactiveFormsModule,
} from '@angular/forms';
import { UsersForm } from '@features/users/components/users-form/users-form';
import { httpResource } from '@angular/common/http';

@Component({
  selector: 'app-user-edit',
  imports: [ReactiveFormsModule, UsersForm],
  templateUrl: './user-edit.html',
  styleUrl: './user-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserEdit {
  private route = inject(ActivatedRoute);
  private userService = inject(UserService);
  private fb = inject(NonNullableFormBuilder);
  private router = inject(Router);

  userId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  private userResource = httpResource<User>(
    () => (this.userId() ? `${this.userService.apiUrl}/${this.userId()}` : undefined),
    { defaultValue: { id: '', username: '', email: '', name: '', lastname: '', phone: '', enabled: true } as User }
  );

  userForm = signal<FormGroup<UserControl>>(this.createForm());

  constructor() {
    effect(() => {
      const user = this.userResource.value();
      if (user && user.id) {
        this.userForm.set(
          this.fb.group({
            id: this.fb.control(user.id),
            username: this.fb.control(user.username, Validators.required),
            email: this.fb.control(user.email, [Validators.required, Validators.email]),
            password: this.fb.control(''),
            name: this.fb.control(user.name),
            lastname: this.fb.control(user.lastname),
            phone: this.fb.control(user.phone),
            enabled: this.fb.control(user.enabled),
          }),
        );
      }
    });
  }

  private createForm(): FormGroup<UserControl> {
    return this.fb.group({
      id: this.fb.control(''),
      username: this.fb.control('', Validators.required),
      email: this.fb.control('', [Validators.required, Validators.email]),
      password: this.fb.control(''),
      name: this.fb.control(''),
      lastname: this.fb.control(''),
      phone: this.fb.control(''),
      enabled: this.fb.control(true),
    });
  }

  onSaveUser(userData: User): void {
    if (userData.id === '') {
      // Remove password if empty for creation
      const { password, ...dataWithoutPassword } = userData;
      this.userService.createUser(userData).subscribe({
        next: () => {
          this.router.navigate(['/users']);
        },
      });
    } else {
      this.userService.updateUser(userData).subscribe({
        next: () => {
          this.router.navigate(['/users']);
        },
      });
    }
  }

  cancelForm(): void {
    this.router.navigate(['/users']);
  }
}
