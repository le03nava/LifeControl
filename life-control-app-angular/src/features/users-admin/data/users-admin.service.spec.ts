import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { UsersAdminService } from './users-admin.service';
import { Role, RoleRequest, UserSearchResult, PageResponse, RoleAssignmentRequest, ChildRoleRequest } from '../models/users-admin.models';
import { ConfigService } from '@app/services/config.service';

describe('UsersAdminService', () => {
  let service: UsersAdminService;
  let httpMock: HttpTestingController;
  let configServiceMock: Partial<ConfigService>;

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
    configServiceMock = {
      apiUrl: 'http://localhost:9000/api',
    };

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
      expect(service.loading()).toBe(false);
    });
  });

  describe('createRealmRole', () => {
    it('should POST /roles/realm with RoleRequest', async () => {
      const request: RoleRequest = { name: 'new-role', description: 'New role', composite: false };

      const rolePromise = firstValueFrom(service.createRealmRole(request));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockRole);

      const role = await rolePromise;
      expect(role).toEqual(mockRole);
    });
  });

  describe('getRealmRole', () => {
    it('should GET /roles/realm/{name}', async () => {
      const rolePromise = firstValueFrom(service.getRealmRole('admin-role'));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/admin-role');
      expect(req.request.method).toBe('GET');
      req.flush(mockRole);

      const role = await rolePromise;
      expect(role).toEqual(mockRole);
    });

    it('should encode special characters in role name', async () => {
      const rolePromise = firstValueFrom(service.getRealmRole('role with spaces'));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/role%20with%20spaces');
      expect(req.request.method).toBe('GET');
      req.flush(mockRole);

      const role = await rolePromise;
      expect(role).toEqual(mockRole);
    });
  });

  describe('updateRealmRole', () => {
    it('should PUT /roles/realm/{name} with RoleRequest', async () => {
      const request: RoleRequest = { name: 'admin-role', description: 'Updated', composite: true };

      const rolePromise = firstValueFrom(service.updateRealmRole('admin-role', request));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/admin-role');
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual(request);
      req.flush(mockRole);

      const role = await rolePromise;
      expect(role).toEqual(mockRole);
    });
  });

  describe('deleteRealmRole', () => {
    it('should DELETE /roles/realm/{name}', async () => {
      const deletePromise = firstValueFrom(service.deleteRealmRole('admin-role'));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/admin-role');
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      await deletePromise;
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
      expect(service.loading()).toBe(false);
    });
  });

  describe('createClientRole', () => {
    it('should POST /roles/client/{clientId} with RoleRequest', async () => {
      const request: RoleRequest = { name: 'client-role', description: 'Client role' };

      const rolePromise = firstValueFrom(service.createClientRole('my-client', request));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/client/my-client');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(mockRole);

      const role = await rolePromise;
      expect(role).toEqual(mockRole);
    });
  });

  // ─── Composite Children ───────────────────────────────────

  describe('addChildRole', () => {
    it('should POST /roles/realm/{parentName}/children with ChildRoleRequest', async () => {
      const request: ChildRoleRequest = { childRole: 'child-role', scope: 'realm' };

      const addPromise = firstValueFrom(service.addChildRole('parent-role', request));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/parent-role/children');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(null);

      await addPromise;
    });
  });

  describe('removeChildRole', () => {
    it('should DELETE /roles/realm/{parentName}/children/{childName}', async () => {
      const removePromise = firstValueFrom(service.removeChildRole('parent-role', 'child-role'));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/roles/realm/parent-role/children/child-role');
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      await removePromise;
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
      expect(service.loading()).toBe(false);
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
    it('should GET /users/{id}/roles', async () => {
      const mockRoles: Role[] = [mockRole];

      const rolesPromise = firstValueFrom(service.getUserRoles('user-1'));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/users/user-1/roles');
      expect(req.request.method).toBe('GET');
      req.flush(mockRoles);

      const roles = await rolesPromise;
      expect(roles).toEqual(mockRoles);
    });
  });

  describe('assignRealmRole', () => {
    it('should POST /users/{id}/roles/realm with RoleAssignmentRequest', async () => {
      const request: RoleAssignmentRequest = { roleName: 'admin-role', scope: 'realm' };

      const assignPromise = firstValueFrom(service.assignRealmRole('user-1', request));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/users/user-1/roles/realm');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(null);

      await assignPromise;
    });
  });

  describe('assignClientRole', () => {
    it('should POST /users/{id}/roles/client/{clientId} with RoleAssignmentRequest', async () => {
      const request: RoleAssignmentRequest = { roleName: 'client-role', scope: 'client', clientId: 'my-client' };

      const assignPromise = firstValueFrom(service.assignClientRole('user-1', 'my-client', request));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/users/user-1/roles/client/my-client');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(request);
      req.flush(null);

      await assignPromise;
    });
  });

  describe('removeRealmRole', () => {
    it('should DELETE /users/{id}/roles/realm/{roleName}', async () => {
      const removePromise = firstValueFrom(service.removeRealmRole('user-1', 'admin-role'));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/users/user-1/roles/realm/admin-role');
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      await removePromise;
    });
  });

  describe('removeClientRole', () => {
    it('should DELETE /users/{id}/roles/client/{clientId}/{roleName}', async () => {
      const removePromise = firstValueFrom(service.removeClientRole('user-1', 'my-client', 'client-role'));

      const req = httpMock.expectOne(
        'http://localhost:9000/api/users-admin/users/user-1/roles/client/my-client/client-role',
      );
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      await removePromise;
    });
  });

  // ─── User Attributes ──────────────────────────────────────

  describe('getUserAttributes', () => {
    it('should GET /users/{id}/attributes', async () => {
      const mockAttrs: Record<string, string[]> = { department: ['engineering'] };

      const attrsPromise = firstValueFrom(service.getUserAttributes('user-1'));

      const req = httpMock.expectOne('http://localhost:9000/api/users-admin/users/user-1/attributes');
      expect(req.request.method).toBe('GET');
      req.flush(mockAttrs);

      const attrs = await attrsPromise;
      expect(attrs).toEqual(mockAttrs);
    });
  });

  describe('updateUserAttribute', () => {
    it('should PUT /users/{id}/attributes/{key} with values array', async () => {
      const values = ['value1', 'value2'];

      const updatePromise = firstValueFrom(service.updateUserAttribute('user-1', 'custom-key', values));

      const req = httpMock.expectOne(
        'http://localhost:9000/api/users-admin/users/user-1/attributes/custom-key',
      );
      expect(req.request.method).toBe('PUT');
      expect(req.request.body).toEqual({ values });
      req.flush(null);

      await updatePromise;
    });
  });

  describe('deleteUserAttribute', () => {
    it('should DELETE /users/{id}/attributes/{key}', async () => {
      const deletePromise = firstValueFrom(service.deleteUserAttribute('user-1', 'custom-key'));

      const req = httpMock.expectOne(
        'http://localhost:9000/api/users-admin/users/user-1/attributes/custom-key',
      );
      expect(req.request.method).toBe('DELETE');
      req.flush(null);

      await deletePromise;
    });
  });
});
