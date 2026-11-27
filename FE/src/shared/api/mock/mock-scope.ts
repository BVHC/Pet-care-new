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

/**
 * T67: `payment` chưa có controller (T65). Chỉ khi ĐẶT ĐƠN đi BE thật mà `pay` còn mock thì cổng thanh toán đóng: `pay` mock tra theo id của kho mock,
 * không biết đơn thật (404, hoặc trùng id một đơn mock của khách mock có cùng account id). Toàn mock giữ cổng giả lập của T57; toàn thật gọi `pay` thật.
 * Khi T65 xong và luật `pay` gỡ khỏi VITE_API_MOCK_ONLY thì điều kiện tự hết, không phải sửa mã.
 */
export function paymentClosedForRealOrders(apiMock: boolean, rules: MockRule[]): boolean {
  return !apiMock && !isMockedRequest('POST', '/api/me/online-orders', rules) && isMockedRequest('POST', '/api/me/online-orders/0/pay', rules);
}

