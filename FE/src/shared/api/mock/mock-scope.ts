// Phạm vi mock của chế độ hỗn hợp (VITE_API_MOCK_ONLY): đường dẫn nào vẫn trả bằng mock khi BE thật đã chạy.
// Mỗi mục: `[METHOD ]/duong/dan`; khớp theo tiền tố ở ranh giới đoạn; `*` thay đúng một đoạn. Ví dụ:
//   /api/public/branches, GET /api/pets/*/health-record

export interface MockRule {
  method?: string;
  segments: string[];
}

const segmentsOf = (path: string) => path.split('/').filter(Boolean);

export function parseMockOnly(raw: string | undefined): MockRule[] {
  const rules: MockRule[] = [];
  for (const entry of (raw ?? '').split(',')) {
    const parts = entry.trim().split(/\s+/);
    const path = parts.pop();
    if (!path || !path.startsWith('/') || parts.length > 1) continue;
    rules.push(parts.length ? { method: parts[0].toUpperCase(), segments: segmentsOf(path) } : { segments: segmentsOf(path) });
  }
  return rules;
}

export function isMockedRequest(method: string, pathname: string, rules: MockRule[]): boolean {
  const path = segmentsOf(pathname);
  return rules.some(
    (rule) =>
      (!rule.method || rule.method === method.toUpperCase()) &&
      rule.segments.length <= path.length &&
      rule.segments.every((s, i) => s === '*' || s === path[i]),
  );
}
