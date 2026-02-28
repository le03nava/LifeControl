import { Component, input, output } from '@angular/core';
import { User } from '../../models/user.models';

@Component({
  selector: 'app-users-card',
  standalone: true,
  imports: [],
  templateUrl: './users-card.html',
  styleUrl: './users-card.scss',
})
export class UsersCard {
  user = input<User | undefined>();
  editUser = output<string>();
  deleteUser = output<{ id: string; name: string }>();

  onEditUser(event?: MouseEvent): void {
    if (event) {
      event.stopPropagation();
    }
    if (this.user()?.id) {
      this.editUser.emit(this.user()!.id);
    }
  }

  onDeleteUser(event: MouseEvent): void {
    event.stopPropagation();
    if (this.user()?.id && this.user()?.username) {
      this.deleteUser.emit({ id: this.user()!.id, name: this.user()!.username });
    }
  }
}
