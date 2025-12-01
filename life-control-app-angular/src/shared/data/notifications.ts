import { Injectable, signal } from '@angular/core';

export interface Notification {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  message: string;
  duration?: number;
}

/**
 * Notification service using signals
 * Manages toast notifications reactively
 */
@Injectable({
  providedIn: 'root',
})
export class NotificationService {
  private notifications = signal<Notification[]>([]);

  /**
   * Read-only access to notifications
   */
  notifications$ = this.notifications.asReadonly();

  /**
   * Show a success notification
   */
  showSuccess(message: string, duration = 5000): void {
    this.addNotification('success', message, duration);
  }

  /**
   * Show an error notification
   */
  showError(message: string, duration = 8000): void {
    this.addNotification('error', message, duration);
  }

  /**
   * Show a warning notification
   */
  showWarning(message: string, duration = 6000): void {
    this.addNotification('warning', message, duration);
  }

  /**
   * Show an info notification
   */
  showInfo(message: string, duration = 5000): void {
    this.addNotification('info', message, duration);
  }

  /**
   * Remove a notification by ID
   */
  removeNotification(id: string): void {
    this.notifications.update((notifications) => notifications.filter((n) => n.id !== id));
  }

  /**
   * Clear all notifications
   */
  clearAll(): void {
    this.notifications.set([]);
  }

  private addNotification(type: Notification['type'], message: string, duration: number): void {
    const notification: Notification = {
      id: this.generateId(),
      type,
      message,
      duration,
    };

    this.notifications.update((notifications) => [...notifications, notification]);

    // Auto-remove after duration
    if (duration > 0) {
      setTimeout(() => {
        this.removeNotification(notification.id);
      }, duration);
    }
  }

  private generateId(): string {
    return Math.random().toString(36).substring(2, 9);
  }
}
