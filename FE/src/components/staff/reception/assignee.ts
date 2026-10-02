export const UNASSIGNED = '';

/** picked: null = follow the suggestion, '' = leave unassigned for now, id = that person while still eligible. */
export function resolveAssignee(picked: string | null, eligible: string[]): string {
  if (picked === UNASSIGNED) return UNASSIGNED;
  if (picked && eligible.includes(picked)) return picked;
  return eligible[0] ?? UNASSIGNED;
}
