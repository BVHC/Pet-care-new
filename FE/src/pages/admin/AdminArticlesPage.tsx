import type { ReactNode } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { ArticleCategoriesTab } from './articles/ArticleCategoriesTab';
import { ArticlesTab } from './articles/ArticlesTab';

const TABS: { key: string; label: string; render: () => ReactNode }[] = [
  { key: 'articles', label: 'Bài viết', render: () => <ArticlesTab /> },
  { key: 'categories', label: 'Chuyên mục', render: () => <ArticleCategoriesTab /> },
];

/** T47 — soạn, đăng, ẩn, xóa bài viết và quản lý chuyên mục (UC81, chỉ SUPER_MANAGER). Mỗi tab một URL. Giữ nguyên <h1> "Bài viết" (route test dựa vào đó). */
export function AdminArticlesPage() {
  const { tab } = useParams();
  const navigate = useNavigate();
  if (!TABS.some((t) => t.key === tab)) return <Navigate to="/admin/articles/articles" replace />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Bài viết</h1>
        <p className="text-(--text-secondary)">
          Soạn và đăng tin cho trang công khai. Bài đã từng xuất bản chỉ ẩn được, không xóa; chuyên mục còn bài chỉ ẩn được.
        </p>
      </div>
      <Tabs value={tab} onValueChange={(value) => navigate(`/admin/articles/${value}`)}>
        <TabsList>
          {TABS.map((t) => (
            <TabsTrigger key={t.key} value={t.key}>
              {t.label}
            </TabsTrigger>
          ))}
        </TabsList>
        {TABS.map((t) => (
          <TabsContent key={t.key} value={t.key} className="mt-4">
            {t.render()}
          </TabsContent>
        ))}
      </Tabs>
    </div>
  );
}
