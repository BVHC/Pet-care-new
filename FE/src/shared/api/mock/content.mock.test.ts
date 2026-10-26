import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import type { PublicArticle, PublicArticleCard, PublicArticleCategory } from '../../types/public-site';
import { CONTENT_STATE_KEY, seedContent } from './content.seed';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (content.seed.ts): 4 chuyên mục, trong đó "Lưu trú & dịch vụ" ẩn nhưng có 1 bài PUBLISHED; 10 bài:
// 8 PUBLISHED, 1 DRAFT (meo-cao-sofa…), 1 HIDDEN (cho-so-phao-hoa…).
let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number };
const articles = async (params: Record<string, unknown> = {}) =>
  (await client.get('/api/public/articles', { params })).data.data as Page<PublicArticleCard>;
const slugs = (list: { slug: string }[]) => list.map((a) => a.slug);

const DRAFT = 'meo-cao-sofa-5-cach-ngan-chan';
const HIDDEN = 'cho-so-phao-hoa-4-buoc-binh-tinh';
const IN_HIDDEN_CATEGORY = 'chon-khach-san-thu-cung-uy-tin';
const NEWEST = 'royal-canin-vs-pedigree-chon-hang-nao-cho-cho-cung';

/** Ghi đè trạng thái mock như T47 sẽ làm khi xuất bản / gỡ bài. */
function edit(change: (state: ReturnType<typeof seedContent>) => void) {
  const state = seedContent();
  change(state);
  localStorage.setItem(CONTENT_STATE_KEY, JSON.stringify(state));
}

describe('bài viết công khai (public, UC17; BR-BV-03, BR-CK-01)', () => {
  it('không cần đăng nhập; chỉ bài PUBLISHED, mới xuất bản trước', async () => {
    const res = await client.get('/api/public/articles');
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ page: 0, size: 20, totalElements: 8, totalPages: 1 });
    const list = res.data.data.content as PublicArticleCard[];
    expect(list).toHaveLength(8);
    expect(slugs(list)).not.toContain(DRAFT);
    expect(slugs(list)).not.toContain(HIDDEN);
    expect(list[0].slug).toBe(NEWEST);
    const times = list.map((a) => Date.parse(a.publishedAt));
    expect(times).toEqual([...times].sort((a, b) => b - a));
    expect(new Set(times).size).toBe(8);
  });

  it('thẻ bài chỉ có các trường của PublicArticleCard (không content, status); publishedAt có offset +07:00', async () => {
    const card = (await articles()).content[0];
    expect(Object.keys(card).sort()).toEqual(
      ['articleId', 'authorDisplayName', 'categoryName', 'categorySlug', 'coverImageUrl', 'publishedAt', 'slug', 'summary', 'title'].sort(),
    );
    expect(card.publishedAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\+07:00$/);
    expect(card).toMatchObject({ title: expect.any(String), categorySlug: expect.any(String), authorDisplayName: 'Dr. Minh Thu' });
  });

  it('có bài không ảnh bìa (coverImageUrl null) và ảnh bìa là đường dẫn nội bộ /imgs/ của mock', async () => {
    const list = (await articles()).content;
    expect(list.some((a) => a.coverImageUrl == null)).toBe(true);
    expect(list.filter((a) => a.coverImageUrl != null).every((a) => a.coverImageUrl!.startsWith('/imgs/'))).toBe(true);
  });

  describe('lọc theo chuyên mục (categorySlug)', () => {
    it('chuyên mục thường', async () => {
      const res = await articles({ categorySlug: 'suc-khoe-thu-y' });
      expect(res.totalElements).toBe(3);
      expect(res.content.every((a) => a.categorySlug === 'suc-khoe-thu-y')).toBe(true);
    });

    it('chuyên mục ẩn không có trong danh sách chuyên mục nhưng vẫn lọc được bài đã xuất bản (BR-BV-04)', async () => {
      const categories = (await client.get('/api/public/article-categories')).data.data as PublicArticleCategory[];
      expect(categories.map((c) => c.slug)).not.toContain('luu-tru-dich-vu');
      expect(slugs((await articles({ categorySlug: 'luu-tru-dich-vu' })).content)).toEqual([IN_HIDDEN_CATEGORY]);
    });

    it('slug chuyên mục không có thì trang rỗng 200, không phải 404', async () => {
      const res = await client.get('/api/public/articles', { params: { categorySlug: 'khong-co' } });
      expect(res.status).toBe(200);
      expect(res.data.data).toMatchObject({ content: [], totalElements: 0, totalPages: 0 });
    });

    it('chuyên mục chỉ có bài DRAFT / HIDDEN thì lọc ra trang rỗng', async () => {
      edit((s) => {
        for (const a of s.articles) if (a.categoryId === 3) a.status = 'HIDDEN';
      });
      expect((await articles({ categorySlug: 'cham-soc-hang-ngay' })).content).toEqual([]);
    });
  });

  describe('phân trang: page từ 0, size 1–100 (mặc định 20), ngoài khoảng thì kẹp, không báo lỗi', () => {
    it('chia trang giữ thứ tự mới nhất trước', async () => {
      const all = slugs((await articles()).content);
      const first = await articles({ size: 3 });
      expect(first).toMatchObject({ page: 0, size: 3, totalElements: 8, totalPages: 3 });
      expect(slugs(first.content)).toEqual(all.slice(0, 3));
      expect(slugs((await articles({ page: 2, size: 3 })).content)).toEqual(all.slice(6));
      expect(await articles({ page: 9, size: 3 })).toMatchObject({ content: [], page: 9, totalElements: 8 });
    });

    it.each([
      [{ page: -1 }, 0, 20],
      [{ size: 0 }, 0, 1],
      [{ size: -5 }, 0, 1],
      [{ size: 101 }, 0, 100],
    ])('kẹp %j thành page %i, size %i', async (params, page, size) => {
      const res = await articles(params);
      expect(res).toMatchObject({ page, size });
      expect(res.content.length).toBe(Math.min(size, 8));
    });

    it('page / size không phải số nguyên thì 400 MALFORMED_REQUEST', async () => {
      expect((await failure(client.get('/api/public/articles', { params: { page: 'abc' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
      expect((await failure(client.get('/api/public/articles', { params: { size: 'x' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
    });
  });

  it('đọc trạng thái từ state, không theo hằng seed: gỡ một bài thì biến khỏi danh sách, đăng bài nháp thì hiện lên đầu', async () => {
    edit((s) => {
      s.articles.find((a) => a.slug === NEWEST)!.status = 'HIDDEN';
      const draft = s.articles.find((a) => a.slug === DRAFT)!;
      draft.status = 'PUBLISHED';
      draft.firstPublishedAt = '2026-10-20T08:00:00+07:00';
    });
    const list = slugs((await articles()).content);
    expect(list).not.toContain(NEWEST);
    expect(list[0]).toBe(DRAFT);
    expect(list).toHaveLength(8);
  });
});

describe('đọc bài viết công khai (public, UC17; BR-BV-03)', () => {
  it('bài PUBLISHED: 200, đúng các trường của PublicArticle, content là HTML an toàn (ADR-0071)', async () => {
    const res = await client.get(`/api/public/articles/${NEWEST}`);
    const article = res.data.data as PublicArticle;
    expect(Object.keys(article).sort()).toEqual(
      ['articleId', 'authorDisplayName', 'categoryName', 'categorySlug', 'content', 'coverImageUrl', 'publishedAt', 'slug', 'summary', 'title'].sort(),
    );
    expect(article).toMatchObject({ slug: NEWEST, categorySlug: 'kien-thuc-nuoi-thu-cung', categoryName: 'Kiến thức nuôi thú cưng' });
    expect(article.content).toContain('<p>');
    expect(article.content).not.toMatch(/<script|<iframe|\son\w+\s*=|javascript:/i);
  });

  it('mọi bài mẫu chỉ dùng thẻ HTML trong danh sách cho phép, không có link / ảnh ngoài https', async () => {
    for (const slug of slugs((await articles()).content)) {
      const { content } = (await client.get(`/api/public/articles/${slug}`)).data.data as PublicArticle;
      const tags = [...content.matchAll(/<\/?([a-z0-9]+)/gi)].map((m) => m[1].toLowerCase());
      expect(tags.length).toBeGreaterThan(0);
      expect(tags.every((t) => ['p', 'h2', 'h3', 'ul', 'ol', 'li', 'strong', 'em', 'br'].includes(t))).toBe(true);
      expect(content).not.toMatch(/(href|src)\s*=\s*["']?(?!https:)/i);
    }
  });

  it('bài trong chuyên mục ẩn vẫn đọc được theo đường dẫn (BR-BV-04)', async () => {
    const res = await client.get(`/api/public/articles/${IN_HIDDEN_CATEGORY}`);
    expect(res.data.data).toMatchObject({ categorySlug: 'luu-tru-dich-vu' });
  });

  it('bài DRAFT, bài HIDDEN, slug lạ và slug sai định dạng cùng một 404 (không lộ bài tồn tại)', async () => {
    const shape = async (slug: string) => {
      const res = await failure(client.get(`/api/public/articles/${encodeURIComponent(slug)}`));
      return { status: res.status, errorCode: res.data.errorCode, message: String(res.data.message).replace(slug, '<slug>') };
    };
    const unknown = await shape('khong-co-bai-nay');
    expect(unknown).toMatchObject({ status: 404, errorCode: 'RESOURCE_NOT_FOUND' });
    expect(await shape(DRAFT)).toEqual(unknown);
    expect(await shape(HIDDEN)).toEqual(unknown);
    expect(await shape('Sai_Dinh_Dang!')).toEqual(unknown);
  });

  it('bài PUBLISHED bị gỡ (HIDDEN) thì 404 ngay', async () => {
    edit((s) => {
      s.articles.find((a) => a.slug === NEWEST)!.status = 'HIDDEN';
    });
    expect((await failure(client.get(`/api/public/articles/${NEWEST}`))).status).toBe(404);
  });
});

describe('chuyên mục bài viết công khai (public, UC17; BR-BV-04)', () => {
  it('bỏ chuyên mục ẩn, chỉ ba trường, theo tên', async () => {
    const res = await client.get('/api/public/article-categories');
    const list = res.data.data as PublicArticleCategory[];
    expect(list.map((c) => c.slug).sort()).toEqual(['cham-soc-hang-ngay', 'kien-thuc-nuoi-thu-cung', 'suc-khoe-thu-y']);
    expect(Object.keys(list[0]).sort()).toEqual(['categoryId', 'name', 'slug']);
    const names = list.map((c) => c.name);
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b, 'vi')));
  });

  it('chuyên mục vừa bị ẩn thì biến khỏi danh sách', async () => {
    edit((s) => {
      s.categories.find((c) => c.slug === 'suc-khoe-thu-y')!.isHidden = true;
    });
    const list = (await client.get('/api/public/article-categories')).data.data as PublicArticleCategory[];
    expect(list.map((c) => c.slug)).not.toContain('suc-khoe-thu-y');
  });
});

describe('dữ liệu mẫu và quyền gọi', () => {
  it('slug bài / chuyên mục duy nhất, đúng định dạng BE; chỉ DRAFT chưa có firstPublishedAt; mọi bài trỏ tới chuyên mục có thật', () => {
    const { articles, categories } = seedContent();
    const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
    for (const slugList of [articles.map((a) => a.slug), categories.map((c) => c.slug)]) {
      expect(new Set(slugList).size).toBe(slugList.length);
      expect(slugList.every((s) => SLUG.test(s))).toBe(true);
    }
    for (const a of articles) {
      expect(a.firstPublishedAt !== null).toBe(a.status !== 'DRAFT');
      expect(categories.some((c) => c.categoryId === a.categoryId)).toBe(true);
    }
  });

  it('cả bài DRAFT / HIDDEN (T47 sẽ đăng / hiện lại) chỉ dùng thẻ cho phép, không link / ảnh ngoài https', () => {
    for (const { slug, content } of seedContent().articles) {
      const tags = [...content.matchAll(/<\/?([a-z0-9]+)/gi)].map((m) => m[1].toLowerCase());
      expect(tags.every((t) => ['p', 'h2', 'h3', 'ul', 'ol', 'li', 'strong', 'em', 'br'].includes(t)), slug).toBe(true);
      expect(content, slug).not.toMatch(/(href|src)\s*=\s*["']?(?!https:)/i);
    }
  });

  it('token gửi kèm (kể cả sai) bị bỏ qua ở path công khai (ADR-0005)', async () => {
    const res = await client.get('/api/public/articles', { headers: { Authorization: 'Bearer het-han' } });
    expect(res.status).toBe(200);
  });
});
