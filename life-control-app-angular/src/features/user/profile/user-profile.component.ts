import { Component, computed, inject, ChangeDetectionStrategy } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import Keycloak from 'keycloak-js';

@Component({
  selector: 'app-user-profile',
  standalone: true,
  imports: [MatCardModule, MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './user-profile.component.html',
  styleUrl: './user-profile.component.scss',
})
export class UserProfileComponent {
  private readonly keycloak = inject(Keycloak);

  readonly displayName = computed(() => {
    const token = this.keycloak.tokenParsed;
    return token?.['name'] || token?.['preferred_username'] || 'Not available';
  });

  readonly email = computed(() => {
    const token = this.keycloak.tokenParsed;
    return token?.['email'] || 'Not available';
  });

  readonly username = computed(() => {
    const token = this.keycloak.tokenParsed;
    return token?.['preferred_username'] || 'Not available';
  });
}
