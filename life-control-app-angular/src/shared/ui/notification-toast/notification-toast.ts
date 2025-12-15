import { Component, inject } from '@angular/core';
import { NotificationService } from '@shared/data';
import { notificationAnimation } from './notification-animation';

@Component({
  selector: 'app-notification-toast',
  standalone: true,
  templateUrl: './notification-toast.html',
  styleUrl: './notification-toast.scss',
  animations: [notificationAnimation],
})
export class NotificationToast {
  notificationService = inject(NotificationService);

  notifications = this.notificationService.notifications$;

  removeNotification(id: string): void {
    this.notificationService.removeNotification(id);
  }

  getNotificationIcon(type: string): string {
    switch (type) {
      case 'success':
        return '✅';
      case 'error':
        return '❌';
      case 'warning':
        return '⚠️';
      case 'info':
        return 'ℹ️';
      default:
        return 'ℹ️';
    }
  }
}