import { TestBed } from '@angular/core/testing';
import { Notification, NotificationService } from './notification';

describe('NotificationService', () => {
  let service: NotificationService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [NotificationService] });
    service = TestBed.inject(NotificationService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('should start with no notifications', () => {
    expect(service.notifications$()).toEqual([]);
  });

  describe('showError', () => {
    it('should add a notification of type error', () => {
      service.showError('Something failed', 0);

      const notifications = service.notifications$();
      expect(notifications.length).toBe(1);
      expect(notifications[0].message).toBe('Something failed');
      expect(notifications[0].type).toBe('error');
      expect(notifications[0].id).toBeDefined();
    });

    it('should accept a custom duration', () => {
      service.showError('Failed', 12000);

      expect(service.notifications$()[0].duration).toBe(12000);
    });
  });

  describe('showSuccess', () => {
    it('should add a notification of type success', () => {
      service.showSuccess('Saved!', 0);

      const notifications = service.notifications$();
      expect(notifications.length).toBe(1);
      expect(notifications[0].message).toBe('Saved!');
      expect(notifications[0].type).toBe('success');
      expect(notifications[0].id).toBeDefined();
    });
  });

  describe('showWarning / showInfo', () => {
    it('should add notifications with warning and info types', () => {
      service.showWarning('Careful', 0);
      service.showInfo('Heads up', 0);

      const notifications = service.notifications$();
      expect(notifications).toHaveLength(2);
      expect(notifications.map((n) => n.type)).toContain('warning');
      expect(notifications.map((n) => n.type)).toContain('info');
    });
  });

  describe('removeNotification', () => {
    it('should remove the notification from the array by id', () => {
      service.showError('Error one', 0);
      service.showSuccess('Success one', 0);
      const [first] = service.notifications$();

      service.removeNotification(first.id);

      const remaining = service.notifications$();
      expect(remaining).toHaveLength(1);
      expect(remaining.some((n: Notification) => n.id === first.id)).toBe(false);
      expect(remaining[0].type).toBe('success');
    });

    it('should do nothing when the id does not exist', () => {
      service.showSuccess('Only one', 0);

      service.removeNotification('non-existent-id');

      expect(service.notifications$()).toHaveLength(1);
    });
  });

  describe('clearAll', () => {
    it('should remove all notifications', () => {
      service.showError('E', 0);
      service.showSuccess('S', 0);

      service.clearAll();

      expect(service.notifications$()).toEqual([]);
    });
  });
});
