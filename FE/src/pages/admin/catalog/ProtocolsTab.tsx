import { useState } from 'react';
import { Edit, Eye, EyeOff, Plus } from 'lucide-react';
import { Button } from '../../../components/ui/button';
import { cn } from '../../../lib/utils';
import { Card, CardContent } from '../../../components/ui/card';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { catalogApi } from '../../../shared/api/catalog.api';
import { plainMessage, toApiError } from '../../../shared/api/api-error';
import { useCatalogMutation, useProtocols, useVaccineTypes } from '../../../shared/hooks/useCatalog';
import { SPECIES_LABELS, type Species, type VaccinationProtocol, type VaccineType } from '../../../shared/types/catalog';
import { ActiveBadge, Checkbox, EmptyRow, Field, FormError, NoticeBar, selectClass, type Notice } from '../../../components/admin/form-ui';

const SPECIES = Object.keys(SPECIES_LABELS) as Species[];

/** UC31 — phác đồ tiêm (BR-SP-02, 03): mỗi dòng là một mũi của một loại vaccine cho một loài. */
export function ProtocolsTab() {
  const [species, setSpecies] = useState('');
  const [vaccineTypeId, setVaccineTypeId] = useState('');
  const protocols = useProtocols({
    species: (species || undefined) as Species | undefined,
    vaccineTypeId: vaccineTypeId ? Number(vaccineTypeId) : undefined,
  });
  const vaccineTypes = useVaccineTypes();
  const [editing, setEditing] = useState<VaccinationProtocol | 'new' | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const toggle = useCatalogMutation((p: VaccinationProtocol) => catalogApi.updateProtocol(p.protocolId, { isActive: !p.isActive }));

  const typeName = (id: number) => vaccineTypes.data?.find((v) => v.vaccineTypeId === id)?.name ?? `#${id}`;

  const onToggle = async (p: VaccinationProtocol) => {
    try {
      await toggle.mutateAsync(p);
      setNotice({ kind: 'success', text: `${p.isActive ? 'Đã ngừng' : 'Đã dùng lại'} mũi ${p.doseNumber} ${typeName(p.vaccineTypeId)}` });
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) });
    }
  };

  return (
    <div className="space-y-4">
      <Card className="card">
        <CardContent className="p-4 flex flex-wrap items-center gap-3">
          <select aria-label="Lọc loài" className={cn(selectClass, 'w-40')} value={species} onChange={(e) => setSpecies(e.target.value)}>
            <option value="">Mọi loài</option>
            {SPECIES.map((s) => (
              <option key={s} value={s}>
                {SPECIES_LABELS[s]}
              </option>
            ))}
          </select>
          <select aria-label="Lọc loại vaccine" className={cn(selectClass, 'w-56')} value={vaccineTypeId} onChange={(e) => setVaccineTypeId(e.target.value)}>
            <option value="">Mọi loại vaccine</option>
            {vaccineTypes.data?.map((v) => (
              <option key={v.vaccineTypeId} value={v.vaccineTypeId}>
                {`${v.name} · ${SPECIES_LABELS[v.species]}`}
              </option>
            ))}
          </select>
          <div className="flex-1" />
          <Button onClick={() => setEditing('new')}>
            <Plus className="h-4 w-4" />
            Thêm dòng phác đồ
          </Button>
        </CardContent>
      </Card>

      <NoticeBar notice={notice} />

      <Card className="card">
        <CardContent className="p-0 overflow-hidden">
          <table className="table">
            <thead>
              <tr>
                <th>Loài</th>
                <th>Loại vaccine</th>
                <th className="text-right">Mũi</th>
                <th className="text-right">Cách mũi kế</th>
                <th className="text-right">Tuổi tối thiểu</th>
                <th>Bắt buộc khi lưu trú</th>
                <th>Trạng thái</th>
                <th className="text-right">Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {protocols.data?.length ? (
                protocols.data.map((p) => (
                  <tr key={p.protocolId}>
                    <td className="text-(--text-secondary)">{SPECIES_LABELS[p.species]}</td>
                    <td className="font-medium text-(--text-primary)">{typeName(p.vaccineTypeId)}</td>
                    <td className="text-right">{p.doseNumber}</td>
                    <td className="text-right">{p.intervalDays} ngày</td>
                    <td className="text-right">{p.minAgeWeeks} tuần</td>
                    <td>{p.requiredForBoarding ? 'Bắt buộc' : 'Không'}</td>
                    <td>
                      <ActiveBadge active={p.isActive} />
                    </td>
                    <td>
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setEditing(p)} aria-label={`Sửa mũi ${p.doseNumber} ${typeName(p.vaccineTypeId)}`} title="Sửa">
                          <Edit className="h-4 w-4" />
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => onToggle(p)}
                          aria-label={`${p.isActive ? 'Ngừng' : 'Dùng lại'} mũi ${p.doseNumber} ${typeName(p.vaccineTypeId)}`}
                          title={p.isActive ? 'Ngừng áp dụng' : 'Dùng lại'}
                        >
                          {p.isActive ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                <EmptyRow colSpan={8} loading={protocols.isLoading} />
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>

      {editing && (
        <ProtocolForm
          protocol={editing === 'new' ? null : editing}
          vaccineTypes={vaccineTypes.data ?? []}
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

interface ProtocolDraft {
  species: Species | '';
  vaccineTypeId: string;
  doseNumber: string;
  intervalDays: string;
  minAgeWeeks: string;
  requiredForBoarding: boolean;
}

const draftOf = (p: VaccinationProtocol | null): ProtocolDraft => ({
  species: p?.species ?? '',
  vaccineTypeId: p ? String(p.vaccineTypeId) : '',
  doseNumber: p ? String(p.doseNumber) : '',
  intervalDays: p ? String(p.intervalDays) : '',
  minAgeWeeks: p ? String(p.minAgeWeeks) : '',
  requiredForBoarding: p?.requiredForBoarding ?? false,
});

const isWhole = (v: string) => v !== '' && Number.isInteger(Number(v));

function ProtocolForm({
  protocol,
  vaccineTypes,
  onClose,
  onSaved,
}: {
  protocol: VaccinationProtocol | null;
  vaccineTypes: VaccineType[];
  onClose: () => void;
  onSaved: (notice: string) => void;
}) {
  const [form, setForm] = useState(() => draftOf(protocol));
  const [error, setError] = useState('');
  const set = (patch: Partial<ProtocolDraft>) => setForm((f) => ({ ...f, ...patch }));
  // BR-SP-02: loài của phác đồ phải trùng loài của loại vaccine.
  const typesOfSpecies = vaccineTypes.filter(
    (v) => v.species === form.species && (v.isActive || String(v.vaccineTypeId) === form.vaccineTypeId),
  );

  const save = useCatalogMutation((f: ProtocolDraft) => {
    const editable = {
      intervalDays: Number(f.intervalDays),
      minAgeWeeks: Number(f.minAgeWeeks),
      requiredForBoarding: f.requiredForBoarding,
    };
    return protocol
      ? catalogApi.updateProtocol(protocol.protocolId, editable)
      : catalogApi.createProtocol({
          ...editable,
          species: f.species as Species,
          vaccineTypeId: Number(f.vaccineTypeId),
          doseNumber: Number(f.doseNumber),
        });
  });

  const submit = async () => {
    const invalid =
      (!form.species && 'Chọn loài.') ||
      (!form.vaccineTypeId && 'Chọn loại vaccine.') ||
      (!(isWhole(form.doseNumber) && Number(form.doseNumber) >= 1) && 'Mũi thứ mấy phải là số nguyên từ 1.') ||
      (!(isWhole(form.intervalDays) && Number(form.intervalDays) > 0) && 'Khoảng cách đến mũi kế tiếp phải lớn hơn 0 ngày') ||
      (!(isWhole(form.minAgeWeeks) && Number(form.minAgeWeeks) >= 0) && 'Tuổi tối thiểu phải là số tuần từ 0.');
    if (invalid) return setError(invalid);
    try {
      const saved = await save.mutateAsync(form);
      const name = vaccineTypes.find((v) => v.vaccineTypeId === saved.vaccineTypeId)?.name ?? '';
      onSaved(protocol ? `Đã lưu mũi ${saved.doseNumber} ${name}` : `Đã thêm mũi ${saved.doseNumber} ${name}`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={protocol ? 'Sửa dòng phác đồ' : 'Thêm dòng phác đồ'}
      description={
        protocol
          ? 'Thay đổi chỉ áp dụng cho mũi tiêm sau thời điểm sửa; ngày tái chủng đã tính giữ nguyên (BR-SP-03).'
          : 'Mỗi dòng là một mũi của một loại vaccine cho một loài.'
      }
      onSubmit={submit}
      submitText={protocol ? 'Lưu thay đổi' : 'Thêm vào phác đồ'}
      loading={save.isPending}
      size="lg"
    >
      <div className="grid grid-cols-3 gap-4">
        <Field label="Loài">
          {(id) => (
            <select
              id={id}
              className={selectClass}
              value={form.species}
              disabled={!!protocol}
              onChange={(e) => set({ species: e.target.value as Species, vaccineTypeId: '' })}
            >
              <option value="">— Chọn loài —</option>
              {SPECIES.map((s) => (
                <option key={s} value={s}>
                  {SPECIES_LABELS[s]}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Loại vaccine">
          {(id) => (
            <select
              id={id}
              className={selectClass}
              value={form.vaccineTypeId}
              disabled={!!protocol || !form.species}
              onChange={(e) => set({ vaccineTypeId: e.target.value })}
            >
              <option value="">{form.species ? '— Chọn loại —' : 'Chọn loài trước'}</option>
              {typesOfSpecies.map((v) => (
                <option key={v.vaccineTypeId} value={v.vaccineTypeId}>
                  {v.name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Mũi thứ">
          {(id) => <Input id={id} type="number" min={1} step={1} value={form.doseNumber} disabled={!!protocol} onChange={(e) => set({ doseNumber: e.target.value })} />}
        </Field>
      </div>
      <div className="grid grid-cols-2 gap-4">
        <Field label="Khoảng cách tới mũi kế (ngày)">
          {(id) => <Input id={id} type="number" min={1} step={1} value={form.intervalDays} onChange={(e) => set({ intervalDays: e.target.value })} />}
        </Field>
        <Field label="Tuổi tối thiểu (tuần)">
          {(id) => <Input id={id} type="number" min={0} step={1} value={form.minAgeWeeks} onChange={(e) => set({ minAgeWeeks: e.target.value })} />}
        </Field>
      </div>
      <Checkbox
        label="Bắt buộc phải đủ mũi này khi gửi lưu trú (BR-LT-05)"
        checked={form.requiredForBoarding}
        onChange={(c) => set({ requiredForBoarding: c })}
      />
      <FormError message={error} />
    </FormModal>
  );
}
