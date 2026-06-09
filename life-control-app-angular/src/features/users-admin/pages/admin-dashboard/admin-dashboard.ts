import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageHeader } from '@shared/ui';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

@Component({
  standalone: true,
  imports: [RouterLink, PageHeader, MatCardModule, MatIconModule],
  templateUrl: './admin-dashboard.html',
  styleUrl: './admin-dashboard.scss',
})
export class AdminDashboard {}
