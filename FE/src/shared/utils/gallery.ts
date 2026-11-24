// Danh sách URL ảnh sản phẩm (trang chi tiết và form quản trị): bỏ khoảng trắng hai đầu, bỏ rỗng, giữ lần xuất hiện đầu của URL trùng.
export const uniqueUrls = (urls: readonly (string | null | undefined)[]): string[] => [
  ...new Set(urls.map((u) => u?.trim() ?? '').filter(Boolean)),
];
