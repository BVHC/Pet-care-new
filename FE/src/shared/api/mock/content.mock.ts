// Mock bài viết công khai (T46, UC17): chuyên mục, danh sách và chi tiết bài (content-v1 #15–17; BR-BV-03, BR-BV-04, BR-CK-01).
// Luật chép từ BE (PublicArticleService, ArticleCategoryService.listPublic). Path công khai: token gửi kèm bị bỏ qua (ADR-0005).
// State (chuyên mục + bài DRAFT / PUBLISHED / HIDDEN) đọc từ localStorage ở CONTENT_STATE_KEY, dữ liệu mẫu ở content.seed.ts; CRUD
// quản trị (UC81, T47) sẽ thêm `save` và route ghi vào file này, trang công khai tự thấy bài vừa đăng / gỡ vì luôn đọc lại state.
//
// Các điểm hợp đồng im lặng (ASSUMPTION):
//  - Slug sai định dạng ở /articles/:slug không bị từ chối riêng: BE tra thẳng theo slug nên cũng là 404 như slug lạ.
//  - `categorySlug` tra trong mọi chuyên mục, kể cả ẩn (D6 của T32); chỉ riêng danh sách chuyên mục công khai bỏ chuyên mục ẩn.
import type { PublicArticle, PublicArticleCard } from '../../types/public-site';
import { type ArticleRecord, type ContentState, CONTENT_STATE_KEY, seedContent } from './content.seed';
import { notFound, ok, type MockHandler } from './envelope';
import { clampPaging, pageOf, typeError } from './public.mock';

function load(): ContentState {
  try {
    const raw = localStorage.getItem(CONTENT_STATE_KEY);
    return raw ? (JSON.parse(raw) as ContentState) : seedContent();
  } catch {
    return seedContent();
  }
}

/** Chuyên mục không ẩn, theo tên rồi id. */
const listPublicArticleCategories: MockHandler = () =>
  ok(
    load()
      .categories.filter((c) => !c.isHidden)
      .sort((a, b) => a.name.localeCompare(b.name, 'vi') || a.categoryId - b.categoryId)
      .map(({ categoryId, name, slug }) => ({ categoryId, name, slug })),
  );

/** Dựng từng trường (không trải bản ghi) để `status`, `content`… không lọt vào thẻ bài. */
function cardOf(a: ArticleRecord, { categories }: ContentState): PublicArticleCard {
  const category = categories.find((c) => c.categoryId === a.categoryId)!;
  return {
    articleId: a.articleId,
    title: a.title,
    slug: a.slug,
    categoryName: category.name,
    categorySlug: category.slug,
    coverImageUrl: a.coverImageUrl,
    summary: a.summary,
    authorDisplayName: a.authorDisplayName,
    publishedAt: a.firstPublishedAt!,
  };
}

/** Chỉ PUBLISHED, xuất bản lần đầu mới nhất trước (rồi id giảm dần); `size` kẹp 1..100 như BE. */
const listPublicArticles: MockHandler = ({ query }) => {
  const bad = typeError(query, ['page', 'size']);
  if (bad) return bad;
  const state = load();
  const paging = clampPaging(query);
  const filtering = !!query.categorySlug?.trim();
  const category = filtering ? state.categories.find((c) => c.slug === query.categorySlug) : undefined;
  if (filtering && !category) return ok(pageOf([], paging));
  const found = state.articles
    .filter((a) => a.status === 'PUBLISHED' && (!category || a.categoryId === category.categoryId))
    .sort((a, b) => Date.parse(b.firstPublishedAt!) - Date.parse(a.firstPublishedAt!) || b.articleId - a.articleId);
  return ok(pageOf(found.map((a) => cardOf(a, state)), paging));
};

/** Bài DRAFT, HIDDEN và slug lạ (kể cả sai định dạng) cùng một 404; bài trong chuyên mục ẩn vẫn đọc được. */
const getPublicArticle: MockHandler = ({ params }) => {
  const state = load();
  const article = state.articles.find((a) => a.slug === params.slug && a.status === 'PUBLISHED');
  if (!article) return notFound('bài viết', params.slug);
  const data: PublicArticle = { ...cardOf(article, state), content: article.content };
  return ok(data);
};

export const contentRoutes: Record<string, MockHandler> = {
  'GET /api/public/article-categories': listPublicArticleCategories,
  'GET /api/public/articles': listPublicArticles,
  'GET /api/public/articles/:slug': getPublicArticle,
};
