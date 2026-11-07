import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { DocumentsTab } from './ai/DocumentsTab';
import { ProfilesTab } from './ai/ProfilesTab';

/** UC93, UC94 — tri thức cho chatbot và hồ sơ gợi ý (chỉ SUPER_MANAGER). Route không có `:tab` nên tab giữ trong state. */
export function AdminAIPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Trí tuệ nhân tạo</h1>
        <p className="text-(--text-secondary)">
          Quản lý tài liệu mà chatbot được phép dùng để trả lời và hồ sơ giúp gợi ý đúng dịch vụ, sản phẩm cho từng thú cưng.
        </p>
      </div>
      <Tabs defaultValue="documents">
        <TabsList>
          <TabsTrigger value="documents">Tài liệu tri thức</TabsTrigger>
          <TabsTrigger value="profiles">Hồ sơ gợi ý</TabsTrigger>
        </TabsList>
        <TabsContent value="documents" className="mt-4">
          <DocumentsTab />
        </TabsContent>
        <TabsContent value="profiles" className="mt-4">
          <ProfilesTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
