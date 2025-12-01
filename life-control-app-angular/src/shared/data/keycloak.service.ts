import { Injectable, signal, computed, inject } from '@angular/core';
import {
  KEYCLOAK_EVENT_SIGNAL,
  KeycloakEventType,
  ReadyArgs,
  typeEventArgs,
} from 'keycloak-angular';
import Keycloak from 'keycloak-js';

/**
 * Global loading state service using signals
 * Tracks loading states across the application
 */
@Injectable({
  providedIn: 'root',
})
export class KeyCloakService {
  authenticated = false;
  keycloakStatus: string | undefined;
  private readonly keycloak = inject(Keycloak);
  private readonly keycloakSignal = inject(KEYCLOAK_EVENT_SIGNAL);

  isAuthenticated(): boolean {
    const keycloakEvent = this.keycloakSignal();

    this.keycloakStatus = keycloakEvent.type;

    if (keycloakEvent.type === KeycloakEventType.Ready) {
      this.authenticated = typeEventArgs<ReadyArgs>(keycloakEvent.args);
    }

    if (keycloakEvent.type === KeycloakEventType.AuthLogout) {
      this.authenticated = false;
    }
    return this.authenticated;
  }

  login() {
    this.keycloak.login();
  }

  logout() {
    this.keycloak.logout();
  }
}
