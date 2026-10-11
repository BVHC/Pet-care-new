import { useState } from 'react';
import { Edit, Eye, EyeOff, Plus, Trash2 } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { cn } from '../../../lib/utils';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { ConfirmModal, FormModal } from '../../../components/ui/modal-templates';
import { catalogApi } from '../../../shared/api/catalog.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useCatalogMutation, useVaccineTypes } from '../../../shared/hooks/useCatalog';
import { SPECIES_LABELS, type Species, type VaccineType } from '../../../shared/types/catalog';
import { ActiveBadge, EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from './ui';

const SPECIES = Object.keys(SPECIES_LABELS) as Species[];

/** UC31 — loại vaccine (BR-SP-07): tạo, đổi tên, ngừng; chỉ xóa được loại chưa dùng. */
export function VaccineTypesTab() {
  const [species, setSpecies] = useState('');
  const types = useVaccineTypes((species || undefined) as Species | undefined);
  const [editing, setEditing] = useState<VaccineType | 'new' | null>(null);
  const [deleting, setDeleting] = useState<VaccineType | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const toggle = useCatalogMutation((v: VaccineType) => catalogApi.updateVaccineType(v.vaccineTypeId, { isActive: !v.isActive }));
  const remove = useCatalogMutation((v: VaccineType) => catalogApi.deleteVaccineType(v.vaccineTypeId));

  const run = async (action: () => Promise<unknown>, success: string) => {
    try {
      await action();
      setNotice({ kind: 'success', text: success });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  const confirmDelete = async () => {
    const target = deleting!;
    setDeleting(null);
    await run(() => remove.mutateAsync(target), `Đã xóa loại vaccine ${target.name}`);
  };

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="p-4 flex flex-wrap items-center justify-between gap-3">
          <select aria-label="Lọc loài" className={cn(selectClass, 'w-40')} value={species} onChange={(e) => setSpecies(e.target.value)}>
            <option value="">Mọi loài</option>
            {SPECIES.map((s) => (
              <option key={s} value={s}>
                {SPECIES_LABELS[s]}
              </option>
            ))}
          </select>
          <Button onClick={() => setEditing('new')}>
            <Plus className="h-4 w-4" />
            Thêm loại vaccine
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Tên loại vaccine</th>
                <th>Loài</th>
                <th>Trạng thái</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {types.data?.length ? (
                types.data.map((v) => (
                  <tr key={v.vaccineTypeId}>
                    <td className="font-medium text-(--text-primary)">{v.name}</td>
                    <td className="text-(--text-secondary)">{SPECIES_LABELS[v.species]}</td>
                    <td>
                      <ActiveBadge active={v.isActive} />
                    </td>
                    <td>
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(v)} aria-label={`Sửa ${v.name}`} title="Đổi tên">
                          <Edit className="h-4 w-4" />
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => run(() => toggle.mutateAsync(v), `${v.isActive ? 'Đã ngừng' : 'Đã dùng lại'} loại vaccine ${v.name}`)}
                          aria-label={`${v.isActive ? 'Ngừng' : 'Dùng lại'} ${v.name}`}
                          title={v.isActive ? 'Ngừng sử dụng' : 'Dùng lại'}
                        >
                          {v.isActive ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => setDeleting(v)}
                          aria-label={`Xóa ${v.name}`}
                          title="Xóa"
                          className="text-red-500 hover:text-red-600"
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={4} loading={types.isLoading} />
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {editing && (
        <VaccineTypeForm
          type={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(text) => {
            setEditing(null);
            setNotice({ kind: 'success', text });
          }}
        />
      )}

      <ConfirmModal
        open={!!deleting}
        onOpenChange={(open) => !open && setDeleting(null)}
        type="danger"
        title={`Xóa loại vaccine ${deleting?.name ?? ''}?`}
        description="Chỉ xóa được loại chưa có phác đồ, sản phẩm hay mũi tiêm nào dùng (BR-SP-07). Loại đã dùng thì chọn Ngừng sử dụng."
        confirmText="Xóa"
        onConfirm={confirmDelete}
        loading={remove.isPending}
      />
    </div>
  );
}

function VaccineTypeForm({ type, onClose, onSaved }: { type: VaccineType | null; onClose: () => void; onSaved: (notice: string) => void }) {
  const [name, setName] = useState(type?.name ?? '');
  const [species, setSpecies] = useState<Species | ''>(type?.species ?? '');
  const [error, setError] = useState('');
  const save = useCatalogMutation(() =>
    type
      ? catalogApi.updateVaccineType(type.vaccineTypeId, { name: name.trim() })
      : catalogApi.createVaccineType({ name: name.trim(), species: species as Species }),
  );

  const submit = async () => {
    const invalid = (!name.trim() && 'Nhập tên loại vaccine.') || (!species && 'Chọn loài áp dụng.');
    if (invalid) return setError(invalid);
    try {
      const saved = await save.mutateAsync(undefined);
      onSaved(type ? `Đã đổi tên thành ${saved.name}` : `Đã thêm loại vaccine ${saved.name}`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={type ? 'Đổi tên loại vaccine' : 'Thêm loại vaccine'}
      description="Ví dụ: Dại, 5 bệnh (chó), 4 bệnh (mèo). Tên không trùng trong cùng một loài."
      onSubmit={submit}
      submitText={type ? 'Lưu thay đổi' : 'Tạo loại vaccine'}
      loading={save.isPending}
    >
      <Field label="Tên loại vaccine">
        {(id) => <Input id={id} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}
      </Field>
      <Field label="Loài áp dụng">
        {(id) => (
          <select id={id} className={selectClass} value={species} disabled={!!type} onChange={(e) => setSpecies(e.target.value as Species)}>
            <option value="">— Chọn loài —</option>
            {SPECIES.map((s) => (
              <option key={s} value={s}>
                {SPECIES_LABELS[s]}
              </option>
            ))}
          </select>
        )}
      </Field>
      <FormError message={error} />
    </FormModal>
  );
}
