import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { UserList } from './user-list';
import { UserService } from '@features/users/data/user.service';
import { of } from 'rxjs';

describe('UserList', () => {
  let component: UserList;
  let fixture: ComponentFixture<UserList>;
  let userService: jasmine.SpyObj<UserService>;

  const mockUsers = [
    { id: '1', name: 'User 1', email: 'user1@test.com', role: 'admin' },
    { id: '2', name: 'User 2', email: 'user2@test.com', role: 'user' },
  ];

  beforeEach(async () => {
    const serviceSpy = jasmine.createSpyObj('UserService', ['getUsers', 'getUserList', 'deleteUser'], {
      users: mockUsers,
    });

    await TestBed.configureTestingModule({
      imports: [UserList],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: UserService, useValue: serviceSpy },
      ],
    }).compileComponents();

    userService = TestBed.inject(UserService) as jasmine.SpyObj<UserService>;
    fixture = TestBed.createComponent(UserList);
    component = fixture.componentRef.instance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should have default search value', () => {
    expect(component.searchQuery()).toBe('');
  });

  it('should update search query on set', () => {
    component.searchQuery.set('test');
    expect(component.searchQuery()).toBe('test');
  });

  it('should clear search query on clearSearch', () => {
    component.searchQuery.set('test');
    component.clearSearch();
    expect(component.searchQuery()).toBe('');
  });

  it('should set delete modal state on confirmDelete', () => {
    component.confirmDelete({ id: '1', name: 'User 1' });
    expect(component.showDeleteModal()).toBeTrue();
    expect(component.userToDelete()).toEqual({ id: '1', name: 'User 1' });
  });

  it('should clear delete modal state on cancelDelete', () => {
    component.confirmDelete({ id: '1', name: 'User 1' });
    component.cancelDelete();
    expect(component.showDeleteModal()).toBeFalse();
    expect(component.userToDelete()).toBeNull();
  });

  it('should call deleteUser and reload on executeDelete', () => {
    userService.deleteUser.and.returnValue(of(void 0));
    component.confirmDelete({ id: '1', name: 'User 1' });
    component.executeDelete();

    expect(userService.deleteUser).toHaveBeenCalledWith('1');
    expect(component.showDeleteModal()).toBeFalse();
    expect(component.userToDelete()).toBeNull();
    expect(userService.getUserList).toHaveBeenCalled();
  });

  it('should not execute delete if no user selected', () => {
    component.executeDelete();
    expect(userService.deleteUser).not.toHaveBeenCalled();
  });

  it('should load users on init', () => {
    expect(userService.getUsers).toHaveBeenCalled();
  });
});
