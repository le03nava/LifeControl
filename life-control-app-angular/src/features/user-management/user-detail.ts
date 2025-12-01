import { Component, inject, signal, computed } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TitleCasePipe } from '@angular/common';
import { UserService } from './data/user';
import { Button } from '@shared/ui/button/button';

/**
 * User detail component showing individual user information
 */
@Component({
  selector: 'app-user-detail',
  imports: [RouterLink, Button, TitleCasePipe],
  template: `
    @if (user(); as userDetail) {
      <div class="user-detail-page">
        <header class="page-header">
          <div class="header-content">
            <app-button variant="secondary" size="small" routerLink="/users">
              ← Back to Users
            </app-button>
            <h1 class="page-title">{{ userDetail.name }}</h1>
            <p class="page-subtitle">User Details</p>
          </div>

          <div class="header-actions">
            <app-button variant="secondary" [routerLink]="['/users', userDetail.id, 'edit']">
              ✏️ Edit User
            </app-button>
          </div>
        </header>

        <div class="user-detail-content">
          <div class="user-profile">
            <div class="user-avatar-large">{{ userDetail.avatar || '👤' }}</div>
            <div class="user-info">
              <h2 class="user-name">{{ userDetail.name }}</h2>
              <p class="user-email">{{ userDetail.email }}</p>
              <span class="user-role" [class]="userDetail.role">
                {{ userDetail.role }}
              </span>
            </div>
          </div>

          <div class="user-details">
            <h3 class="details-title">Account Information</h3>

            <div class="details-grid">
              <div class="detail-item">
                <label class="detail-label">User ID</label>
                <span class="detail-value">{{ userDetail.id }}</span>
              </div>

              <div class="detail-item">
                <label class="detail-label">Full Name</label>
                <span class="detail-value">{{ userDetail.name }}</span>
              </div>

              <div class="detail-item">
                <label class="detail-label">Email Address</label>
                <span class="detail-value">{{ userDetail.email }}</span>
              </div>

              <div class="detail-item">
                <label class="detail-label">Role</label>
                <span class="detail-value">{{ userDetail.role | titlecase }}</span>
              </div>

              <div class="detail-item">
                <label class="detail-label">Account Status</label>
                <span class="detail-value status active">Active</span>
              </div>

              <div class="detail-item">
                <label class="detail-label">Last Login</label>
                <span class="detail-value">{{ lastLoginDate() }}</span>
              </div>
            </div>
          </div>

          <div class="user-activity">
            <h3 class="details-title">Recent Activity</h3>
            <div class="activity-list">
              @for (activity of recentActivity(); track activity.id) {
                <div class="activity-item">
                  <div class="activity-time">{{ activity.time }}</div>
                  <div class="activity-description">{{ activity.description }}</div>
                </div>
              } @empty {
                <div class="empty-activity">
                  <p>No recent activity recorded</p>
                </div>
              }
            </div>
          </div>
        </div>
      </div>
    } @else {
      <div class="user-not-found">
        <h1>User Not Found</h1>
        <p>The requested user could not be found.</p>
        <button app-button variant="primary" routerLink="/users">Back to Users</button>
      </div>
    }
  `,
  styles: [
    `
      .user-detail-page {
        max-width: 1000px;
        margin: 0 auto;
      }

      .page-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        margin-bottom: var(--space-2xl);
        gap: var(--space-lg);
      }

      .header-content {
        display: flex;
        flex-direction: column;
        gap: var(--space-sm);
      }

      .page-title {
        margin: 0;
        font-size: 2.5rem;
        font-weight: 700;
        color: var(--color-gray-900);
      }

      .page-subtitle {
        margin: 0;
        font-size: 1.125rem;
        color: var(--color-gray-600);
      }

      .user-detail-content {
        display: grid;
        gap: var(--space-2xl);
      }

      .user-profile {
        background: white;
        border: 1px solid var(--color-gray-200);
        border-radius: var(--radius-lg);
        padding: var(--space-2xl);
        display: flex;
        align-items: center;
        gap: var(--space-xl);
      }

      .user-avatar-large {
        font-size: 5rem;
        line-height: 1;
      }

      .user-info {
        flex: 1;
      }

      .user-name {
        margin: 0 0 var(--space-sm);
        font-size: 2rem;
        font-weight: 600;
        color: var(--color-gray-900);
      }

      .user-email {
        margin: 0 0 var(--space-md);
        font-size: 1.125rem;
        color: var(--color-gray-600);
      }

      .user-role {
        display: inline-block;
        padding: var(--space-sm) var(--space-md);
        border-radius: var(--radius-md);
        font-size: 0.875rem;
        font-weight: 500;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .user-role.admin {
        background-color: var(--color-primary);
        color: white;
      }

      .user-role.user {
        background-color: var(--color-gray-200);
        color: var(--color-gray-700);
      }

      .user-details,
      .user-activity {
        background: white;
        border: 1px solid var(--color-gray-200);
        border-radius: var(--radius-lg);
        padding: var(--space-2xl);
      }

      .details-title {
        margin: 0 0 var(--space-lg);
        font-size: 1.25rem;
        font-weight: 600;
        color: var(--color-gray-800);
        padding-bottom: var(--space-sm);
        border-bottom: 1px solid var(--color-gray-200);
      }

      .details-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
        gap: var(--space-lg);
      }

      .detail-item {
        display: flex;
        flex-direction: column;
        gap: var(--space-xs);
      }

      .detail-label {
        font-size: 0.875rem;
        font-weight: 500;
        color: var(--color-gray-500);
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }

      .detail-value {
        font-size: 1rem;
        color: var(--color-gray-900);
        font-weight: 500;
      }

      .detail-value.status.active {
        color: var(--color-success);
      }

      .activity-list {
        display: flex;
        flex-direction: column;
        gap: var(--space-md);
      }

      .activity-item {
        display: flex;
        gap: var(--space-md);
        padding: var(--space-md);
        background: var(--color-gray-50);
        border-radius: var(--radius-md);
        border: 1px solid var(--color-gray-100);
      }

      .activity-time {
        font-size: 0.875rem;
        color: var(--color-gray-500);
        min-width: 80px;
        flex-shrink: 0;
      }

      .activity-description {
        color: var(--color-gray-700);
      }

      .empty-activity {
        text-align: center;
        padding: var(--space-xl);
        color: var(--color-gray-500);
      }

      .user-not-found {
        text-align: center;
        padding: var(--space-2xl);
      }

      @media (max-width: 768px) {
        .page-header {
          flex-direction: column;
          align-items: stretch;
        }

        .user-profile {
          flex-direction: column;
          text-align: center;
        }

        .details-grid {
          grid-template-columns: 1fr;
        }

        .activity-item {
          flex-direction: column;
          gap: var(--space-sm);
        }

        .activity-time {
          min-width: auto;
        }
      }
    `,
  ],
})
export class UserDetail {
  private route = inject(ActivatedRoute);
  private userService = inject(UserService);

  userId = signal(this.route.snapshot.paramMap.get('id'));

  user = computed(() => {
    const id = this.userId();
    return id ? this.userService.getUserById(id) : null;
  });

  lastLoginDate = computed(() => {
    // Mock last login date
    const now = new Date();
    const daysAgo = Math.floor(Math.random() * 7) + 1;
    const lastLogin = new Date(now.getTime() - daysAgo * 24 * 60 * 60 * 1000);
    return lastLogin.toLocaleDateString('en-US', {
      year: 'numeric',
      month: 'long',
      day: 'numeric',
    });
  });

  recentActivity = computed(() => [
    {
      id: '1',
      time: '2 hours ago',
      description: 'Logged into the system',
    },
    {
      id: '2',
      time: '1 day ago',
      description: 'Updated profile information',
    },
    {
      id: '3',
      time: '3 days ago',
      description: 'Changed password',
    },
  ]);
}
