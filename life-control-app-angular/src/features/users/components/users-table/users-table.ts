import { Component, input, output } from '@angular/core';
import { User } from '../../models/user.models';

@Component({
  selector: 'app-users-table',
  standalone: true,
  imports: [],
  templateUrl: './users-table.html',
  styleUrl: './users-table.scss',
})
export class UsersTable {
  users = input<User[]>([]);
  editUser = output<string>();
  deleteUser = output<{ id: string; name: string }>();
}
