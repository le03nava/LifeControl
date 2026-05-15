export type RoleScope = 'realm' | 'client';

export interface Role {
  name: string;
  description?: string;
  composite: boolean;
  scope: RoleScope;
  clientId?: string;
}

export interface RoleRequest {
  name: string;
  description?: string;
  composite?: boolean;
  childRoles?: ChildRoleRequest[];
}

export interface ChildRoleRequest {
  childRole: string;
  scope: RoleScope;
  clientId?: string;
}

export interface UserSearchResult {
  id: string;
  username: string;
  email?: string;
  enabled: boolean;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface UserAttribute {
  key: string;
  values: string[];
}

export interface RoleAssignmentRequest {
  roleName: string;
  scope: RoleScope;
  clientId?: string;
}
