import { useState } from 'react';
import { Pencil, Search } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { StatusBadge } from '../../../components/ui/modal-templates';
import { Checkbox, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';
import { Pager } from '../../../components/admin/Pager';
import { cn } from '../../../lib/utils';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useDebounce } from '../../../shared/hooks/useDebounce';
import { useItemProfiles } from '../../../shared/hooks/useAi';
import { ITEM_TYPES, type ItemProfile, type ItemProfileRow, type ItemType } from '../../../shared/types/ai';
import { TableState } from '../articles/TableState';
import { GROUP_LABELS, ITEM_TYPE_LABELS, PROFILE_SPECIES_LABELS } from './labels';
import { ProfileForm } from './ProfileForm';

const PAGE_SIZE = 20;

/** Tóm tắt các thuộc tính đang đặt, vd "Chó · 8–52 tuần · poodle". */
function summaryOf(p: ItemProfile): string {
  const range = (lo?: number | null, hi?: number | null, unit = '') =>
    lo != null && hi != null ? `${lo}–${hi}${unit}` : lo != null ? `từ ${lo}${unit}` : hi != null ? `đến ${hi}${unit}` : null;
  return [
    p.species && PROFILE_SPECIES_LABELS[p.species],
    range(p.minAgeWeeks, p.maxAgeWeeks, ' tuần'),
    range(p.minWeightKg, p.maxWeightKg, ' kg'),
    p.breedKeywords.length > 0 && p.breedKeywords.join(', '),
    p.repeatIntervalDays != null && `dùng lại sau ${p.repeatIntervalDays} ngày`,
  ]
    .filter(Boolean)
    .join(' · ');
}

/** UC94: hồ sơ gợi ý của dịch vụ thẩm mỹ và sản phẩm bán lẻ không kê đơn (BR-AI-18, 20). */
export function ProfilesTab() {
  const [q, setQ] = useState('');
  const search = useDebounce(q.trim());
  const [itemType, setItemType] = useState('');
  const [missingOnly, setMissingOnly] = useState(false);
  const [page, setPage] = useState(0);
  const list = useItemProfiles({ itemType: (itemType || undefined) as ItemType | undefined, missingOnly: missingOnly || undefined, q: search || undefined, page, size: PAGE_SIZE });
  const [editing, setEditing] = useState<ItemProfileRow | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const data = list.data;

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          <div className="relative min-w-[220px] flex-1">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--text-tertiary)" />
            <Input
              aria-label="Tìm theo tên"
              placeholder="Tìm theo tên…"
              className="pl-10"
              maxLength={100}
              value={q}
              onChange={(e) => {
                setQ(e.target.value);
                setPage(0);
              }}
            />
          </div>
          <select
            aria-label="Lọc loại mục"
            className={cn(selectClass, 'sm:w-44')}
            value={itemType}
            onChange={(e) => {
              setItemType(e.target.value);
              setPage(0);
            }}
          >
            <option value="">Dịch vụ và sản phẩm</option>
            {ITEM_TYPES.map((t) => (
              <option key={t} value={t}>
                {ITEM_TYPE_LABELS[t]}
              </option>
            ))}
          </select>
          <Checkbox
            label="Chỉ mục chưa có hồ sơ"
            checked={missingOnly}
            onChange={(checked) => {
              setMissingOnly(checked);
              setPage(0);
            }}
          />
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="overflow-x-auto p-0">
          <table className="table" aria-label="Hồ sơ gợi ý">
            <thead>
              <tr>
                <th className="px-3!">Tên</th>
                <th className="px-3!">Loại</th>
                <th className="px-3!">Hồ sơ</th>
                <th className="px-3!" style={{ textAlign: 'right' }}>
                  Thao tác
                </th>
              </tr>
            </thead>
            <tbody>
              {data?.content.length ? (
                data.content.map((r) => (
                  <tr key={`${r.itemType}-${r.itemId}`}>
                    <td className="px-3! font-medium text-(--text-primary)">{r.name}</td>
                    <td className="whitespace-nowrap px-3!">
                      {ITEM_TYPE_LABELS[r.itemType]} · {GROUP_LABELS[r.group]}
                    </td>
                    <td className="px-3!">
                      {r.hasProfile && r.profile ? (
                        <>
                          <StatusBadge status="Đã có hồ sơ" variant="success" />
                          <p className="mt-1 text-xs text-(--text-secondary)">{summaryOf(r.profile) || 'Chưa đặt thuộc tính nào'}</p>
                        </>
                      ) : (
                        <StatusBadge status="Chưa có hồ sơ" variant="warning" />
                      )}
                    </td>
                    <td className="px-3!">
                      <div className="flex justify-end">
                        <Button size="sm" variant="outline" aria-label={`${r.hasProfile ? 'Sửa' : 'Lập'} hồ sơ ${r.name}`} onClick={() => setEditing(r)}>
                          <Pencil className="h-3.5 w-3.5" />
                          {r.hasProfile ? 'Sửa hồ sơ' : 'Lập hồ sơ'}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <TableState colSpan={4} loading={list.isLoading} error={list.isError ? plainMessage(toApiError(list.error)) : undefined} onRetry={() => list.refetch()}>
                  {itemType || missingOnly || search ? 'Không có mục nào khớp bộ lọc.' : 'Chưa có dịch vụ thẩm mỹ hay sản phẩm nào.'}
                </TableState>
              )}
            </tbody>
          </table>
          {data && <Pager data={data} unit="mục" onPage={setPage} />}
        </CardContent>
      </Card>

      {editing && (
        <ProfileForm
          row={editing}
          onClose={() => setEditing(null)}
          onSaved={(text) => {
            setEditing(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}
