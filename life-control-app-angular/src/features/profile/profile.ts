import { Component, inject } from '@angular/core';
import { TitleCasePipe } from '@angular/common';
import { AuthService } from '@shared/data/auth';
import { Button } from '@shared/ui/button/button';

/**
 * User profile component
 */
@Component({
  selector: 'app-profile',
  imports: [Button, TitleCasePipe],
  template: `
    @if (authService.user(); as user) {
      <div class="profile-page">
        <header class="page-header">
          <h1 class="page-title">My Profile</h1>
          <p class="page-subtitle">Manage your account settings and preferences</p>
        </header>

        <div class="profile-content">
          <div class="profile-card">
            <div class="profile-avatar">{{ user.avatar || '👤' }}</div>
            <div class="profile-info">
              <h2 class="profile-name">{{ user.name }}</h2>
              <p class="profile-email">{{ user.email }}</p>
              <span class="profile-role" [class]="user.role">{{ user.role }}</span>
            </div>
          </div>

          <div class="profile-sections">
            <div class="profile-section">
              <h3 class="section-title">Account Information</h3>
              <div class="info-grid">
                <div class="info-item">
                  <label>Full Name</label>
                  <span>{{ user.name }}</span>
                </div>
                <div class="info-item">
                  <label>Email Address</label>
                  <span>{{ user.email }}</span>
                </div>
                <div class="info-item">
                  <label>Role</label>
                  <span>{{ user.role | titlecase }}</span>
                </div>
                <div class="info-item">
                  <label>User ID</label>
                  <span>{{ user.id }}</span>
                </div>
              </div>
            </div>

            <div class="profile-section">
              <h3 class="section-title">Settings</h3>
              <div class="settings-grid">
                <button app-button variant="secondary" class="setting-button">
                  🔧 Account Settings
                </button>
                <button app-button variant="secondary" class="setting-button">
                  🔐 Change Password
                </button>
                <button app-button variant="secondary" class="setting-button">
                  🔔 Notifications
                </button>
                <button app-button variant="secondary" class="setting-button">
                  🎨 Preferences
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    }
  `,
  styles: [
    `
      .profile-page {
        max-width: 800px;
        margin: 0 auto;
      }

      .page-header {
        margin-bottom: var(--space-2xl);
      }

      .page-title {
        margin: 0 0 var(--space-sm);
        font-size: 2.5rem;
        font-weight: 700;
        color: var(--color-gray-900);
      }

      .page-subtitle {
        margin: 0;
        font-size: 1.125rem;
        color: var(--color-gray-600);
      }

      .profile-content {
        display: grid;
        gap: var(--space-xl);
      }

      .profile-card {
        background: white;
        border: 1px solid var(--color-gray-200);
        border-radius: var(--radius-lg);
        padding: var(--space-2xl);
        display: flex;
        align-items: center;
        gap: var(--space-xl);
      }

      .profile-avatar {
        font-size: 4rem;
        line-height: 1;
      }

      .profile-info {
        flex: 1;
      }

      .profile-name {
        margin: 0 0 var(--space-sm);
        font-size: 1.75rem;
        font-weight: 600;
        color: var(--color-gray-900);
      }

      .profile-email {
        margin: 0 0 var(--space-md);
        font-size: 1.125rem;
        color: var(--color-gray-600);
      }

      .profile-role {
        display: inline-block;
        padding: var(--space-sm) var(--space-md);
        border-radius: var(--radius-md);
        font-size: 0.875rem;
        font-weight: 500;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .profile-role.admin {
        background-color: var(--color-primary);
        color: white;
      }

      .profile-role.user {
        background-color: var(--color-gray-200);
        color: var(--color-gray-700);
      }

      .profile-sections {
        display: grid;
        gap: var(--space-xl);
      }

      .profile-section {
        background: white;
        border: 1px solid var(--color-gray-200);
        border-radius: var(--radius-lg);
        padding: var(--space-xl);
      }

      .section-title {
        margin: 0 0 var(--space-lg);
        font-size: 1.25rem;
        font-weight: 600;
        color: var(--color-gray-800);
        padding-bottom: var(--space-sm);
        border-bottom: 1px solid var(--color-gray-200);
      }

      .info-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: var(--space-lg);
      }

      .info-item {
        display: flex;
        flex-direction: column;
        gap: var(--space-xs);
      }

      .info-item label {
        font-size: 0.875rem;
        font-weight: 500;
        color: var(--color-gray-500);
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .info-item span {
        font-size: 1rem;
        color: var(--color-gray-900);
        font-weight: 500;
      }

      .settings-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: var(--space-md);
      }

      .setting-button {
        justify-content: flex-start;
      }

      @media (max-width: 768px) {
        .profile-card {
          flex-direction: column;
          text-align: center;
        }

        .info-grid,
        .settings-grid {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class Profile {
  authService = inject(AuthService);
}
