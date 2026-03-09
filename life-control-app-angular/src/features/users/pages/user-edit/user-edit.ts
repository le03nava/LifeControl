import { ChangeDetectionStrategy, Component, inject, signal, OnInit } from '@angular/core';
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
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-user-edit',
  imports: [ReactiveFormsModule, UsersForm],
  templateUrl: './user-edit.html',
  styleUrl: './user-edit.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserEdit implements OnInit {
  private route = inject(ActivatedRoute);
  private userService = inject(UserService);
  private fb = inject(NonNullableFormBuilder);
  private router = inject(Router);
  private http = inject(HttpClient);

  userId = signal<string | null>(this.route.snapshot.paramMap.get('id'));

  userForm = signal<FormGroup<UserControl>>(this.createForm());

  ngOnInit(): void {
    const id = this.userId();
    if (id) {
      this.loadUser(id);
    }
  }

  private loadUser(id: string): void {
    this.http.get<User>(`${this.userService.apiUrl}/${id}`).subscribe({
      next: (user) => {
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
          })
        );
      },
      error: (err) => {
        console.error('[UserEdit] Error loading user:', err);
      },
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
