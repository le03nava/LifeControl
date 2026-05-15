import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { UsersAdminService } from './users-admin.service';
import { Role, RoleRequest, UserSearchResult, PageResponse, RoleAssignmentRequest, ChildRoleRequest } from '../models/users-admin.models';
import { ConfigService } from '@app/services/config.service';

describe('UsersAdminService', () => {
  let service: UsersAdminService;
  let httpMock: HttpTestingController;
  let configServiceMock: jasmine.SpyObj<ConfigService>;

  const mockRole: Role = {
    name: 'admin-role',
    description: 'Administrator',
    composite: false,
    scope: 'realm',
    clientId: undefined,
  };

  const mockUser: UserSearchResult = {
    id: 'user-1',
    username: 'testuser',
    email: 'test@example.com',
    enabled: true,
  };

  const mockPage: PageResponse<UserSearchResult> = {
    content: [mockUser],
    page: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
  };

  beforeEach(() => {
    configServiceMock = jasmine.createSpyObj<ConfigService>('ConfigService', [], {
      apiUrl: 'http://localhost:9000/api',
    });

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        UsersAdminService,
        { provide: ConfigService, useValue: configServiceMock },
      ],
    });
    service = TestBed.inject(UsersAdminService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  // ─── Realm Roles ──────────────────────────────────────────

  describe('loadRealmRoles', () => {
    it('should GET /roles/realm and update roles signal', () => {
      const mockRoles: Role[] = [mockRole];

      service.loadRealmRoles();

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm');
      expect(req.request.method).toBe('GET');
      req.flush(mockRoles);

      expect(service.roles()).toEqual(mockRoles);
      expect(service.loading()).toBeFalse();
    });
  });

  describe('createRealmRole', () => {
    it('should POST /roles/realm with RoleRequest', (done) => {
      const request: RoleRequest = { name: 'new-role', description: 'New role', composite: false };

      service.createRealmRole(request).subscribe((role) => {
        expect(role).toEqual(mockRole);
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockRole);
    });
  });

  describe('getRealmRole', () => {
    it('should GET /roles/realm/{name}', (done) => {
      service.getRealmRole('admin-role').subscribe((role) => {
        expect(role).toEqual(mockRole);
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/admin-role');
      expect(req.request.method).toBe('GET');
      req.flush(mockRole);
    });

    it('should encode special characters in role name', (done) => {
      service.getRealmRole('role with spaces').subscribe((role) => {
        expect(role).toEqual(mockRole);
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/role%20with%20spaces');
      expect(req.request.method).toBe('GET');
      req.flush(mockRole);
    });
  });

  describe('updateRealmRole', () => {
    it('should PUT /roles/realm/{name} with RoleRequest', (done) => {
      const request: RoleRequest = { name: 'admin-role', description: 'Updated', composite: true };

      service.updateRealmRole('admin-role', request).subscribe((role) => {
        expect(role).toEqual(mockRole);
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/admin-role');
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(request);
      req.flush(mockRole);
    });
  });

  describe('deleteRealmRole', () => {
    it('should DELETE /roles/realm/{name}', (done) => {
      service.deleteRealmRole('admin-role').subscribe(() => {
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/admin-role');
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });
  });

  // ─── Client Roles ─────────────────────────────────────────

  describe('loadClientRoles', () => {
    it('should GET /roles/client/{clientId} and update roles signal', () => {
      const mockRoles: Role[] = [mockRole];

      service.loadClientRoles('my-client');

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/client/my-client');
      expect(req.request.method).toBe('GET');
      req.flush(mockRoles);

      expect(service.roles()).toEqual(mockRoles);
      expect(service.loading()).toBeFalse();
    });
  });

  describe('createClientRole', () => {
    it('should POST /roles/client/{clientId} with RoleRequest', (done) => {
      const request: RoleRequest = { name: 'client-role', description: 'Client role' };

      service.createClientRole('my-client', request).subscribe((role) => {
        expect(role).toEqual(mockRole);
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/client/my-client');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockRole);
    });
  });

  // ─── Composite Children ───────────────────────────────────

  describe('addChildRole', () => {
    it('should POST /roles/realm/{parentName}/children with ChildRoleRequest', (done) => {
      const request: ChildRoleRequest = { childRole: 'child-role', scope: 'realm' };

      service.addChildRole('parent-role', request).subscribe(() => {
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/parent-role/children');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(null);
    });
  });

  describe('removeChildRole', () => {
    it('should DELETE /roles/realm/{parentName}/children/{childName}', (done) => {
      service.removeChildRole('parent-role', 'child-role').subscribe(() => {
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/parent-role/children/child-role');
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });
  });

  // ─── User Search ──────────────────────────────────────────

  describe('searchUsers', () => {
    it('should GET /users with search params and update users signal', () => {
      service.searchUsers('testuser');

      const req = httpMock.expectOne(
        (r) => r.url === 'http://localhost:9000/api/users-admin/users' && r.method === 'GET',
      );
      expect(req.request.params.get('search')).toBe('testuser');
      expect(req.request.params.get('page')).toBe('0');
      expect(req.request.params.get('size')).toBe('20');
      req.flush(mockPage);

      expect(service.users()).toEqual([mockUser]);
      expect(service.loading()).toBeFalse();
    });

    it('should clear users when query is empty', () => {
      service.searchUsers('   ');

      // No HTTP request should be made for whitespace-only query
      httpMock.expectNone('http://localhost:9000/api/users-admin/users');

      expect(service.users()).toEqual([]);
    });
  });

  // ─── User Roles ───────────────────────────────────────────

  describe('getUserRoles', () => {
    it('should GET /users/{id}/roles', (done) => {
      const mockRoles: Role[] = [mockRole];

      service.getUserRoles('user-1').subscribe((roles) => {
        expect(roles).toEqual(mockRoles);
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/users/user-1/roles');
      expect(req.request.method).toBe('GET');
      req.flush(mockRoles);
    });
  });

  describe('assignRealmRole', () => {
    it('should POST /users/{id}/roles/realm with RoleAssignmentRequest', (done) => {
      const request: RoleAssignmentRequest = { roleName: 'admin-role', scope: 'realm' };

      service.assignRealmRole('user-1', request).subscribe(() => {
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/users/user-1/roles/realm');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(null);
    });
  });

  describe('assignClientRole', () => {
    it('should POST /users/{id}/roles/client/{clientId} with RoleAssignmentRequest', (done) => {
      const request: RoleAssignmentRequest = { roleName: 'client-role', scope: 'client', clientId: 'my-client' };

      service.assignClientRole('user-1', 'my-client', request).subscribe(() => {
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/users/user-1/roles/client/my-client');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(null);
    });
  });

  describe('removeRealmRole', () => {
    it('should DELETE /users/{id}/roles/realm/{roleName}', (done) => {
      service.removeRealmRole('user-1', 'admin-role').subscribe(() => {
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/users/user-1/roles/realm/admin-role');
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });
  });

  describe('removeClientRole', () => {
    it('should DELETE /users/{id}/roles/client/{clientId}/{roleName}', (done) => {
      service.removeClientRole('user-1', 'my-client', 'client-role').subscribe(() => {
        done();
      });

      const req = httpMock.expectOne(
        'http://localhost:9000/api/users-admin/users/user-1/roles/client/my-client/client-role',
      );
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });
  });

  // ─── User Attributes ──────────────────────────────────────

  describe('getUserAttributes', () => {
    it('should GET /users/{id}/attributes', (done) => {
      const mockAttrs: Record<string, string[]> = { department: ['engineering'] };

      service.getUserAttributes('user-1').subscribe((attrs) => {
        expect(attrs).toEqual(mockAttrs);
        done();
      });

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/users/user-1/attributes');
      expect(req.request.method).toBe('GET');
      req.flush(mockAttrs);
    });
  });

  describe('updateUserAttribute', () => {
    it('should PUT /users/{id}/attributes/{key} with values array', (done) => {
      const values = ['value1', 'value2'];

      service.updateUserAttribute('user-1', 'custom-key', values).subscribe(() => {
        done();
      });

      const req = httpMock.expectOne(
        'http://localhost:9000/api/users-admin/users/user-1/attributes/custom-key',
      );
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ values });
      req.flush(null);
    });
  });

  describe('deleteUserAttribute', () => {
    it('should DELETE /users/{id}/attributes/{key}', (done) => {
      service.deleteUserAttribute('user-1', 'custom-key').subscribe(() => {
        done();
      });

      const req = httpMock.expectOne(
        'http://localhost:9000/api/users-admin/users/user-1/attributes/custom-key',
      );
      expect(req.request.method).toBe('DELETE');
      req.flush(null);
    });
  });
});
