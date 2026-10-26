// Trang công khai (UC15–17) — theo docs/api/identity-v1.md (PublicVet), catalog-v1.md (PublicProduct), content-v1.md (PublicArticle*).
// Chi nhánh và dịch vụ công khai: `PublicBranch` (./branch), `PublicService` (./catalog). Trường `?` có thể vắng hoặc null.

/** identity-v1 #37: VET ACTIVE ở chi nhánh ACTIVE (BR-TK-20, BR-CK-01). Không có email / SĐT. */
export interface PublicVet {
  accountId: number;
  fullName: string;
  avatarUrl?: string | null;
  specialty?: string | null;
  bio?: string | null;
  branchId: number;
  branchName: string;
}

/** catalog-v1 #21, #22: không bao giờ có số lượng tồn, chỉ danh sách chi nhánh còn hàng (BR-CK-03). */
export interface PublicProduct {
  productId: number;
  name: string;
  categoryId: number;
  categoryName: string;
  /** VND */
  price: number;
  unit: string;
  description?: string | null;
  imageUrl?: string | null;
  /** Chi nhánh ACTIVE còn tồn khả dụng; ghép tên bằng `branchNamesByIds`. */
  inStockBranchIds: number[];
}

/** `q` tối đa 100 ký tự; `page` từ 0, `size` 1–100 (mặc định 20), ngoài khoảng thì BE kẹp lại. */
export interface PublicProductQuery {
  q?: string;
  categoryId?: number;
  branchId?: number;
  page?: number;
  size?: number;
}

/** content-v1 #15: chuyên mục ẩn không có trong danh sách. */
export interface PublicArticleCategory {
  categoryId: number;
  name: string;
  slug: string;
}

/** content-v1 #16: `publishedAt` là ISO có offset (vd `2026-07-28T09:00:00+07:00`). */
export interface PublicArticleCard {
  articleId: number;
  title: string;
  slug: string;
  categoryName: string;
  categorySlug: string;
  coverImageUrl?: string | null;
  summary?: string | null;
  authorDisplayName: string;
  publishedAt: string;
}

/** content-v1 #17: `content` là HTML đã lọc ở BE (ADR-0071), chỉ render ở trang chi tiết bài. */
export type PublicArticle = PublicArticleCard & { content: string };

export interface PublicArticleQuery {
  categorySlug?: string;
  page?: number;
  size?: number;
}
