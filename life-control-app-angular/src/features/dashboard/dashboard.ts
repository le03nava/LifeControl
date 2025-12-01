import { Component, inject, signal, computed } from '@angular/core';
import { AuthService } from '@shared/data/auth';
import { Button } from '@shared/ui/button/button';

/**
 * Dashboard component demonstrating signal-based reactive programming
 * Features:
 * - Signal-based state management
 * - Computed values for derived state
 * - Modern template syntax with control flow
 */
@Component({
  selector: 'app-dashboard',
  imports: [Button],
  template: `
    <div class="dashboard">
      <header class="dashboard-header">
        <h1 class="page-title">Dashboard</h1>
        <p class="page-subtitle">Welcome back, {{ authService.user()?.name || 'User' }}!</p>
      </header>

      <div class="dashboard-grid">
        <!-- Stats Cards -->
        <div class="stats-section">
          <h2 class="section-title">Quick Stats</h2>
          <div class="stats-grid">
            @for (stat of stats(); track stat.id) {
              <div class="stat-card" [class]="stat.type">
                <div class="stat-icon">{{ stat.icon }}</div>
                <div class="stat-content">
                  <div class="stat-value">{{ stat.value }}</div>
                  <div class="stat-label">{{ stat.label }}</div>
                </div>
              </div>
            }
          </div>
        </div>

        <!-- Recent Activity -->
        <div class="activity-section">
          <h2 class="section-title">Recent Activity</h2>
          <div class="activity-list">
            @for (activity of recentActivity(); track activity.id) {
              <div class="activity-item">
                <div class="activity-time">{{ activity.time }}</div>
                <div class="activity-message">{{ activity.message }}</div>
              </div>
            } @empty {
              <div class="empty-state">
                <p>No recent activity</p>
              </div>
            }
          </div>
        </div>

        <!-- Quick Actions -->
        <div class="actions-section">
          <h2 class="section-title">Quick Actions</h2>
          <div class="actions-grid">
            @for (action of quickActions(); track action.id) {
              <button
                app-button
                [variant]="action.variant"
                (buttonClick)="handleAction(action.id)"
                class="action-button"
              >
                {{ action.icon }} {{ action.label }}
              </button>
            }
          </div>
        </div>

        <!-- System Status -->
        <div class="status-section">
          <h2 class="section-title">System Status</h2>
          <div class="status-grid">
            @for (status of systemStatus(); track status.id) {
              <div class="status-item" [class]="status.status">
                <div class="status-indicator"></div>
                <div class="status-content">
                  <div class="status-name">{{ status.name }}</div>
                  <div class="status-value">{{ status.value }}</div>
                </div>
              </div>
            }
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .dashboard {
        max-width: 1200px;
        margin: 0 auto;
      }

      .dashboard-header {
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

      .dashboard-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
        gap: var(--space-xl);
      }

      .section-title {
        margin: 0 0 var(--space-lg);
        font-size: 1.25rem;
        font-weight: 600;
        color: var(--color-gray-800);
      }

      /* Stats Section */
      .stats-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: var(--space-md);
      }

      .stat-card {
        background: white;
        border-radius: var(--radius-lg);
        padding: var(--space-lg);
        border: 1px solid var(--color-gray-200);
        display: flex;
        align-items: center;
        gap: var(--space-md);
        transition: box-shadow 0.2s ease;
      }

      .stat-card:hover {
        box-shadow: var(--shadow-md);
      }

      .stat-card.primary {
        border-color: var(--color-primary);
        background: linear-gradient(135deg, var(--color-primary) 0%, #1e40af 100%);
        color: white;
      }

      .stat-card.success {
        border-color: var(--color-success);
        background: linear-gradient(135deg, var(--color-success) 0%, #059669 100%);
        color: white;
      }

      .stat-icon {
        font-size: 2rem;
        opacity: 0.8;
      }

      .stat-value {
        font-size: 1.75rem;
        font-weight: 700;
        line-height: 1;
      }

      .stat-label {
        font-size: 0.875rem;
        opacity: 0.8;
        margin-top: var(--space-xs);
      }

      /* Activity Section */
      .activity-list {
        background: white;
        border-radius: var(--radius-lg);
        border: 1px solid var(--color-gray-200);
        overflow: hidden;
      }

      .activity-item {
        padding: var(--space-md);
        border-bottom: 1px solid var(--color-gray-100);
        display: flex;
        gap: var(--space-md);
        align-items: center;
      }

      .activity-item:last-child {
        border-bottom: none;
      }

      .activity-time {
        font-size: 0.875rem;
        color: var(--color-gray-500);
        min-width: 80px;
      }

      .activity-message {
        color: var(--color-gray-700);
      }

      /* Actions Section */
      .actions-grid {
        display: grid;
        gap: var(--space-md);
      }

      .action-button {
        width: 100%;
        justify-content: flex-start;
      }

      /* Status Section */
      .status-grid {
        background: white;
        border-radius: var(--radius-lg);
        border: 1px solid var(--color-gray-200);
        overflow: hidden;
      }

      .status-item {
        padding: var(--space-md);
        border-bottom: 1px solid var(--color-gray-100);
        display: flex;
        align-items: center;
        gap: var(--space-md);
      }

      .status-item:last-child {
        border-bottom: none;
      }

      .status-indicator {
        width: 12px;
        height: 12px;
        border-radius: 50%;
        background-color: var(--color-gray-300);
      }

      .status-item.online .status-indicator {
        background-color: var(--color-success);
      }

      .status-item.warning .status-indicator {
        background-color: var(--color-warning);
      }

      .status-item.offline .status-indicator {
        background-color: var(--color-error);
      }

      .status-name {
        font-weight: 500;
        color: var(--color-gray-700);
      }

      .status-value {
        font-size: 0.875rem;
        color: var(--color-gray-500);
        margin-top: var(--space-xs);
      }

      .empty-state {
        padding: var(--space-xl);
        text-align: center;
        color: var(--color-gray-500);
      }
    `,
  ],
})
export class Dashboard {
  authService = inject(AuthService);

  private currentTime = signal(new Date());

  // Computed signals for derived state
  stats = computed(() => [
    {
      id: 'users',
      icon: '👥',
      value: '1,234',
      label: 'Total Users',
      type: 'primary',
    },
    {
      id: 'revenue',
      icon: '💰',
      value: '$12,345',
      label: 'Revenue',
      type: 'success',
    },
    {
      id: 'orders',
      icon: '📦',
      value: '567',
      label: 'Orders',
      type: 'default',
    },
    {
      id: 'growth',
      icon: '📈',
      value: '+23%',
      label: 'Growth',
      type: 'success',
    },
  ]);

  recentActivity = computed(() => [
    {
      id: '1',
      time: '2m ago',
      message: 'New user registered: john@example.com',
    },
    {
      id: '2',
      time: '5m ago',
      message: 'Order #1234 was completed',
    },
    {
      id: '3',
      time: '10m ago',
      message: 'System backup completed successfully',
    },
    {
      id: '4',
      time: '15m ago',
      message: 'Payment processed for order #1233',
    },
  ]);

  quickActions = computed(() => [
    {
      id: 'new-user',
      icon: '👤',
      label: 'Add New User',
      variant: 'primary' as const,
    },
    {
      id: 'export-data',
      icon: '📊',
      label: 'Export Data',
      variant: 'secondary' as const,
    },
    {
      id: 'system-settings',
      icon: '⚙️',
      label: 'System Settings',
      variant: 'secondary' as const,
    },
  ]);

  systemStatus = computed(() => [
    {
      id: 'api',
      name: 'API Server',
      value: 'Online',
      status: 'online',
    },
    {
      id: 'database',
      name: 'Database',
      value: 'Online',
      status: 'online',
    },
    {
      id: 'cache',
      name: 'Cache Server',
      value: 'Warning - High Memory',
      status: 'warning',
    },
    {
      id: 'storage',
      name: 'File Storage',
      value: 'Online',
      status: 'online',
    },
  ]);

  constructor() {
    // Update time every minute
    setInterval(() => {
      this.currentTime.set(new Date());
    }, 60000);
  }

  handleAction(actionId: string): void {
    switch (actionId) {
      case 'new-user':
        // Navigate to user creation
        console.log('Navigate to user creation');
        break;
      case 'export-data':
        // Export data logic
        console.log('Export data');
        break;
      case 'system-settings':
        // Navigate to settings
        console.log('Navigate to settings');
        break;
    }
  }
}
