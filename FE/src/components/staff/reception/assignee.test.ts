import { describe, expect, it } from 'vitest';
import { UNASSIGNED, resolveAssignee } from './assignee';

describe('resolveAssignee (check-in dialog)', () => {
  const eligible = ['vet-2', '5'];

  it('follows the suggestion until the receptionist picks', () => {
    expect(resolveAssignee(null, eligible)).toBe('vet-2');
  });

  it('keeps an explicit "assign later"', () => {
    expect(resolveAssignee(UNASSIGNED, eligible)).toBe(UNASSIGNED);
  });

  it('keeps the person picked', () => {
    expect(resolveAssignee('5', eligible)).toBe('5');
  });

  it('falls back to the suggestion when the pick no longer fits the service', () => {
    expect(resolveAssignee('6', eligible)).toBe('vet-2');
  });
});
