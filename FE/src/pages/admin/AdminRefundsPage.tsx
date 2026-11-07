/**
 * Trả hàng và hoàn tiền (UC68, UC69) — returns-v1 #1–9. Hai khối: "Yêu cầu trả hàng" (lễ tân tạo / rút, quản lý chi nhánh duyệt / từ chối)
 * và "Hoàn tiền" (lễ tân xác nhận khoản đã đưa khách tại quầy, quản lý chi nhánh thử lại khoản qua cổng thất bại). Nút ngoài vai trò bị ẩn.
 */
import { useState } from 'react';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { RefundsTab } from './refunds/RefundsTab';
import { ReturnRequestsTab } from './refunds/ReturnRequestsTab';

type Block = 'requests' | 'refunds';

export function AdminRefundsPage() {
  const [block, setBlock] = useState<Block>('requests');
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-(--text-primary)">Trả hàng & hoàn tiền</h1>
        <p className="text-(--text-secondary)">Khách trả hàng trong 7 ngày kể từ lúc thanh toán (quầy) hoặc lúc giao (online). Duyệt xong mới sinh khoản hoàn tiền.</p>
      </div>
      <Tabs value={block} onValueChange={(v) => setBlock(v as Block)}>
        <TabsList aria-label="Khối trả hàng và hoàn tiền">
          <TabsTrigger value="requests">Yêu cầu trả hàng</TabsTrigger>
          <TabsTrigger value="refunds">Hoàn tiền</TabsTrigger>
        </TabsList>
      </Tabs>
      {block === 'requests' ? <ReturnRequestsTab /> : <RefundsTab />}
    </div>
  );
}
