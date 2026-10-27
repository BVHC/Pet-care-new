import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Card } from '../../components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { QueryError, RowsSkeleton } from '../../components/staff/ui';
import { PAGE_KEYS, PAGE_LABELS, PAGE_SCHEMAS, parsePageContent } from '../../shared/constants/page-content';
import { usePageContents } from '../../shared/hooks/usePageContent';
import type { PageKey } from '../../shared/types/page-content';
import { sameContent, type Draft } from './page-contents/draft';
import { PageEditor } from './page-contents/PageEditor';

type Drafts = Partial<Record<PageKey, Draft>>;

const without = (drafts: Drafts, key: PageKey): Drafts => Object.fromEntries(Object.entries(drafts).filter(([k]) => k !== key));

/**
 * T47 — sửa chữ 4 trang tĩnh (UC21, chỉ SUPER_MANAGER): banner trang chủ, giới thiệu, chính sách, câu hỏi thường gặp. Tab theo `?trang=<KHÓA>`.
 * Nháp từng tab nằm ở đây (không ở tab) nên đổi tab, hoặc lưu / tải lại danh sách của tab khác, không làm mất chữ đang soạn.
 * Giữ nguyên <h1> "Nội dung trang" (route test dựa vào đó).
 */
export function AdminPageContentsPage() {
  const pages = usePageContents();
  const [params, setParams] = useSearchParams();
  const [drafts, setDrafts] = useState<Drafts>({});
  const active = PAGE_KEYS.find((key) => key === params.get('trang')) ?? PAGE_KEYS[0]!;

  const savedOf = (key: PageKey) => pages.data?.find((page) => page.key === key)?.content ?? {};
  const baseOf = (key: PageKey) => parsePageContent(key, savedOf(key)) as Draft;
  const isDirty = (key: PageKey) => drafts[key] !== undefined && !sameContent(drafts[key], baseOf(key));

  // Nháp trở về đúng bản đã lưu thì bỏ luôn (so sánh thô: dấu cách cuối vẫn là thay đổi).
  const change = (key: PageKey, update: (current: Draft) => Draft) =>
    setDrafts((all) => {
      const next = update(all[key] ?? baseOf(key));
      return sameContent(next, baseOf(key)) ? without(all, key) : { ...all, [key]: next };
    });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Nội dung trang</h1>
        <p className="text-(--text-secondary)">
          Sửa chữ hiển thị trên trang chủ, trang giới thiệu, điều khoản, chính sách bảo mật và câu hỏi thường gặp. Thay đổi có hiệu lực ngay sau khi lưu.
        </p>
      </div>

      {pages.isPending ? (
        <Card>
          <RowsSkeleton rows={5} />
        </Card>
      ) : pages.isError && !pages.data ? (
        <Card>
          <QueryError error={pages.error} onRetry={() => pages.refetch()} what="nội dung trang" />
        </Card>
      ) : (
        <Tabs value={active} onValueChange={(value) => setParams({ trang: value })}>
          <TabsList className="h-auto w-full flex-wrap justify-start gap-1 sm:w-auto">
            {PAGE_KEYS.map((key) => (
              <TabsTrigger key={key} value={key}>
                {PAGE_LABELS[key]}
                {isDirty(key) && (
                  <>
                    {' '}
                    <span className="ml-1.5 rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800">Chưa lưu</span>
                  </>
                )}
              </TabsTrigger>
            ))}
          </TabsList>
          {PAGE_KEYS.map((key) => (
            <TabsContent key={key} value={key} className="mt-4">
              <PageEditor
                pageKey={key}
                base={baseOf(key)}
                isDefault={!PAGE_SCHEMAS[key].safeParse(savedOf(key)).success}
                draft={drafts[key]}
                onChange={(update) => change(key, update)}
                onDiscard={() => setDrafts((all) => without(all, key))}
              />
            </TabsContent>
          ))}
        </Tabs>
      )}
    </div>
  );
}
