import { describe, expect, it } from 'vitest';
import {
  WORKSPACE_PATHS,
  canAccessWorkspace,
  getAccessibleWorkspaces,
  homePathFor,
  workspaceForPath,
} from './workspaces';

// Quyền theo actor A03–A08 (docs/01-business-operations.md), mỗi tài khoản một role.
describe('workspace access', () => {
  it('sends a receptionist to reception and lets them reach the back office', () => {
    expect(getAccessibleWorkspaces('RECEPTIONIST')).toEqual(['reception', 'admin']);
    expect(homePathFor('RECEPTIONIST')).toBe('/staff/reception');
  });

  it('lets the branch manager into reception (UC45 gán lại lượt) but starts them in the back office', () => {
    expect(getAccessibleWorkspaces('BRANCH_MANAGER')).toEqual(['reception', 'admin']);
    expect(homePathFor('BRANCH_MANAGER')).toBe('/admin/dashboard');
  });

  it('keeps a vet in the exam room and away from the desk', () => {
    expect(getAccessibleWorkspaces('VET')).toEqual(['doctor', 'admin']);
    expect(canAccessWorkspace('VET', 'reception')).toBe(false);
    expect(homePathFor('VET')).toBe('/staff/doctor');
  });

  it('keeps a caretaker out of the exam room', () => {
    expect(canAccessWorkspace('CARETAKER', 'grooming')).toBe(true);
    expect(canAccessWorkspace('CARETAKER', 'doctor')).toBe(false);
    expect(homePathFor('CARETAKER')).toBe('/staff/grooming');
  });

  it('gives the chain manager the back office only', () => {
    expect(getAccessibleWorkspaces('SUPER_MANAGER')).toEqual(['admin']);
    expect(homePathFor('SUPER_MANAGER')).toBe('/admin/dashboard');
  });

  it('keeps the technical admin out of clinic work and sends them to accounts', () => {
    expect(getAccessibleWorkspaces('ADMIN')).toEqual([]);
    expect(homePathFor('ADMIN')).toBe('/admin/users');
  });

  it('gives customers no staff workspace and sends them to the home page', () => {
    expect(getAccessibleWorkspaces('CUSTOMER')).toEqual([]);
    expect(homePathFor('CUSTOMER')).toBe('/');
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
