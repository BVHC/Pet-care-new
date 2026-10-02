import { describe, expect, it } from 'vitest';
import type { Role, SessionUser } from '../types/admin';
import {
  WORKSPACE_PATHS,
  canAccessWorkspace,
  getAccessibleWorkspaces,
  getDefaultWorkspace,
  getUserRoles,
  workspaceForPath,
} from './workspaces';

const user = (role: Role, roles?: Role[]): SessionUser => ({
  id: 'u1', email: 'u@petcare.vn', name: 'U', role, roles, organizationId: 'org-1',
});

describe('workspace access', () => {
  it('sends a receptionist to reception and lets them reach the back-office', () => {
    const u = user('RECEPTIONIST');
    expect(getAccessibleWorkspaces(u)).toEqual(['reception', 'admin']);
    expect(getDefaultWorkspace(u)).toBe('reception');
  });

  it('merges workspaces of every role but defaults to the primary role', () => {
    const vetAtDesk = user('VETERINARIAN', ['VETERINARIAN', 'RECEPTIONIST']);
    expect(getAccessibleWorkspaces(vetAtDesk)).toEqual(['reception', 'doctor', 'admin']);
    expect(getDefaultWorkspace(vetAtDesk)).toBe('doctor');
  });

  it('gives a store manager every workspace with admin as home', () => {
    const u = user('STORE_MANAGER');
    expect(getAccessibleWorkspaces(u)).toEqual(['reception', 'doctor', 'grooming', 'admin']);
    expect(getDefaultWorkspace(u)).toBe('admin');
  });

  it('keeps a groomer out of the exam room', () => {
    const u = user('GROOMER');
    expect(canAccessWorkspace(u, 'grooming')).toBe(true);
    expect(canAccessWorkspace(u, 'doctor')).toBe(false);
  });

  it('gives customers no staff workspace', () => {
    const u = user('CUSTOMER');
    expect(getAccessibleWorkspaces(u)).toEqual([]);
    expect(getDefaultWorkspace(u)).toBeNull();
  });

  it('reads legacy sessions that only carry a single role', () => {
    expect(getUserRoles(user('GROOMER'))).toEqual(['GROOMER']);
    expect(getUserRoles(user('GROOMER', []))).toEqual(['GROOMER']);
  });

  it('always lists the primary role first and never twice', () => {
    expect(getUserRoles(user('VETERINARIAN', ['RECEPTIONIST', 'VETERINARIAN']))).toEqual([
      'VETERINARIAN',
      'RECEPTIONIST',
    ]);
  });

  it('maps every workspace to a distinct absolute path', () => {
    const paths = Object.values(WORKSPACE_PATHS);
    expect(new Set(paths).size).toBe(paths.length);
    paths.forEach((p) => expect(p.startsWith('/')).toBe(true));
  });

  it('finds the workspace that owns a path', () => {
    expect(workspaceForPath('/staff/doctor')).toBe('doctor');
    expect(workspaceForPath('/staff/grooming/board')).toBe('grooming');
    expect(workspaceForPath('/admin/dashboard')).toBe('admin');
    expect(workspaceForPath('/shop')).toBeNull();
  });
});
