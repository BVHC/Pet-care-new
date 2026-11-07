import { useState } from 'react';
import { Clock, Plus } from 'lucide-react';
import { ActiveBadge, NoticeBar, type Notice } from '@/components/admin/form-ui';
import { QueryError, RowsSkeleton, StatePanel } from '@/components/staff/ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { workforceApi } from '@/shared/api/workforce.api';
import { useShiftDefinitions, useWorkforceMutation } from '@/shared/hooks/useWorkforce';
import type { ShiftDefinition, UpdateShiftDefinitionRequest } from '@/shared/types/workforce';
import { shiftTime } from './labels';
import { ShiftDefinitionDialog } from './ShiftDefinitionDialog';

/** Ca làm việc của chi nhánh (UC34, workforce-v1 #1–3): tạo, sửa, ngừng dùng / dùng lại. Ngừng dùng khi ca còn phân ca chưa làm thì máy chủ từ chối (BR-NS-01). */
export function ShiftDefinitionsTab() {
  const list = useShiftDefinitions();
  const [editing, setEditing] = useState<ShiftDefinition | 'new' | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const update = useWorkforceMutation(({ id, body }: { id: number; body: UpdateShiftDefinitionRequest }) => workforceApi.updateShiftDefinition(id, body));

  const toggle = async (d: ShiftDefinition) => {
    try {
      await update.mutateAsync({ id: d.shiftDefinitionId, body: { active: !d.active } });
      setNotice({ kind: 'success', text: d.active ? `Đã ngừng dùng ${d.name}.` : `${d.name} dùng lại được.` });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  let body;
  if (list.isError && !list.data) body = <QueryError error={toApiError(list.error)} onRetry={() => list.refetch()} what="ca làm việc" />;
  else if (!list.data) body = <RowsSkeleton rows={4} />;
  else if (list.data.length === 0) {
    body = <StatePanel icon={Clock} title="Chưa có ca làm việc" body="Thêm ca (vd Ca sáng 07:30–12:00) để bắt đầu phân ca cho nhân viên." />;
  } else {
    body = (
      <table className="table" aria-label="Danh sách ca làm việc">
        <thead>
          <tr>
            <th>Tên ca</th>
            <th>Giờ làm</th>
            <th>Trạng thái</th>
            <th className="text-right!">Thao tác</th>
          </tr>
        </thead>
        <tbody>
          {list.data.map((d) => (
            <tr key={d.shiftDefinitionId}>
              <td className="font-medium text-(--text-primary)">{d.name}</td>
              <td className="tabular-nums">{shiftTime(d)}</td>
              <td>
                <ActiveBadge active={d.active} />
              </td>
              <td>
                <div className="flex justify-end gap-1.5">
                  <Button size="sm" variant="outline" onClick={() => setEditing(d)} aria-label={`Sửa ${d.name}`}>
                    Sửa
                  </Button>
                  <Button size="sm" variant="outline" disabled={update.isPending} onClick={() => toggle(d)} aria-label={`${d.active ? 'Ngừng dùng' : 'Dùng lại'} ${d.name}`}>
                    {d.active ? 'Ngừng dùng' : 'Dùng lại'}
                  </Button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-(--text-secondary)">Ca dùng chung cho cả chi nhánh; mỗi phân ca gắn một nhân viên với một ca trong một ngày.</p>
        <Button onClick={() => setEditing('new')}>
          <Plus className="h-4 w-4" aria-hidden /> Thêm ca
        </Button>
      </div>
      <NoticeBar notice={notice} />
      <Card className="card">
        <CardContent className="overflow-x-auto p-0">{body}</CardContent>
      </Card>
      {editing && (
        <ShiftDefinitionDialog
          definition={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onDone={(text) => {
            setEditing(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}
    </div>
  );
}
