import { useState } from 'react';
import { Button } from '../../../components/ui/button';
import { Input } from '../../../components/ui/input';
import { FormModal, Modal, ModalBody, ModalFooter, ModalHeader, ModalTitle } from '../../../components/ui/modal-templates';
import { Field, FormError, selectClass } from '../../../components/admin/form-ui';
import { aiApi } from '../../../shared/api/ai.api';
import { fieldErrorsOf, plainMessage, toApiError, type ApiError } from '../../../shared/api/api-error';
import { useAiDocument, useAiMutation } from '../../../shared/hooks/useAi';
import {
  AI_LIMITS,
  DOCUMENT_SPECIES,
  DOCUMENT_TOPICS,
  type AiDocument,
  type CreateAiDocumentRequest,
  type DocumentSpecies,
  type DocumentTopic,
  type UpdateAiDocumentRequest,
} from '../../../shared/types/ai';
import { INDEX_LABELS, SOURCE_LABELS, SPECIES_LABELS, STATUS_LABELS, TOPIC_LABELS } from './labels';

interface Draft {
  title: string;
  topic: DocumentTopic | '';
  species: DocumentSpecies | '';
  content: string;
  sourceNote: string;
  reviewerName: string;
  reviewedOn: string;
}
type Errors = Partial<Record<keyof Draft, string>>;

const draftOf = (d: AiDocument | null): Draft => ({
  title: d?.title ?? '',
  topic: d?.topic ?? '',
  species: d?.species ?? '',
  content: d?.content ?? '',
  sourceNote: d?.sourceNote ?? '',
  reviewerName: d?.reviewerName ?? '',
  reviewedOn: d?.reviewedOn ?? '',
});

/** BR-AI-14: tài liệu đã xuất bản phải giữ đủ nguồn (lớp 1) và người, ngày rà soát (lớp 2). */
function validate(d: Draft, published: boolean): Errors {
  const e: Errors = {};
  if (!d.title.trim()) e.title = 'Nhập tiêu đề.';
  if (!d.topic) e.topic = 'Chọn chủ đề.';
  if (!d.species) e.species = 'Chọn loài áp dụng.';
  if (!d.content.trim()) e.content = 'Nhập nội dung.';
  if (published) {
    if (!d.sourceNote.trim()) e.sourceNote = 'Tài liệu đã xuất bản phải có ghi chú nguồn.';
    if (!d.reviewerName.trim()) e.reviewerName = 'Nhập tên bác sĩ thú y rà soát.';
    if (!d.reviewedOn) e.reviewedOn = 'Chọn ngày rà soát.';
  }
  return e;
}

const createBody = (d: Draft): CreateAiDocumentRequest => ({
  title: d.title.trim(),
  topic: d.topic as DocumentTopic,
  species: d.species as DocumentSpecies,
  content: d.content,
  ...(d.sourceNote.trim() ? { sourceNote: d.sourceNote.trim() } : {}),
  ...(d.reviewerName.trim() ? { reviewerName: d.reviewerName.trim() } : {}),
  ...(d.reviewedOn ? { reviewedOn: d.reviewedOn } : {}),
});

/** PATCH chỉ trường đã đổi; sửa NỘI DUNG tài liệu đã xuất bản thì luôn gửi kèm người và ngày rà soát (BR-AI-14). */
function changesOf(a: AiDocument, d: Draft): UpdateAiDocumentRequest {
  const p: UpdateAiDocumentRequest = {};
  if (d.title.trim() !== a.title) p.title = d.title.trim();
  if (d.topic && d.topic !== a.topic) p.topic = d.topic;
  if (d.species && d.species !== a.species) p.species = d.species;
  if (d.content !== a.content) p.content = d.content;
  if (d.sourceNote.trim() !== (a.sourceNote ?? '')) p.sourceNote = d.sourceNote.trim();
  if (d.reviewerName.trim() !== (a.reviewerName ?? '')) p.reviewerName = d.reviewerName.trim();
  if (d.reviewedOn && d.reviewedOn !== (a.reviewedOn ?? '')) p.reviewedOn = d.reviewedOn;
  if (a.status === 'PUBLISHED' && p.content !== undefined) Object.assign(p, { reviewerName: d.reviewerName.trim(), reviewedOn: d.reviewedOn });
  return p;
}

/** UC93: tạo nháp (`'new'`) hoặc sửa / xem tài liệu `documentId`. Tài liệu CATALOG_PROTOCOL và ARCHIVED chỉ xem (BR-AI-13; 409 khi sửa tài liệu lưu trữ). */
export function DocumentForm({ documentId, onClose, onSaved }: { documentId: number | 'new'; onClose: () => void; onSaved: (text: string) => void }) {
  const detail = useAiDocument(documentId === 'new' ? null : documentId);
  if (documentId !== 'new' && !detail.data) {
    return (
      <Modal open onOpenChange={(open) => !open && onClose()} className="max-w-md">
        <ModalHeader onClose={onClose}>
          <ModalTitle>Tài liệu tri thức</ModalTitle>
        </ModalHeader>
        <ModalBody className="space-y-4">
          {detail.isError ? (
            <>
              <FormError message={plainMessage(toApiError(detail.error))} />
              <Button variant="outline" onClick={() => detail.refetch()}>
                Thử lại
              </Button>
            </>
          ) : (
            <p className="py-6 text-center text-(--text-tertiary)">Đang tải tài liệu…</p>
          )}
        </ModalBody>
      </Modal>
    );
  }
  return <Form doc={detail.data ?? null} onClose={onClose} onSaved={onSaved} />;
}

function Form({ doc, onClose, onSaved }: { doc: AiDocument | null; onClose: () => void; onSaved: (text: string) => void }) {
  const [original] = useState(doc); // ảnh chụp lúc mở: "đã đổi" tính theo nó
  const [draft, setDraft] = useState(() => draftOf(original));
  const [errors, setErrors] = useState<Errors>({});
  const [failure, setFailure] = useState<ApiError | null>(null);
  const create = useAiMutation(aiApi.createDocument);
  const update = useAiMutation((v: { id: number; patch: UpdateAiDocumentRequest }) => aiApi.updateDocument(v.id, v.patch));
  const readOnly = !!original && (original.sourceType === 'CATALOG_PROTOCOL' || original.status === 'ARCHIVED');
  const published = original?.status === 'PUBLISHED';
  const set = (change: Partial<Draft>) => {
    setDraft((d) => ({ ...d, ...change }));
    setErrors((e) => Object.fromEntries(Object.entries(e).filter(([key]) => !(key in change))));
  };

  const submit = async () => {
    setFailure(null);
    const found = validate(draft, published);
    setErrors(found);
    if (Object.keys(found).length) return;
    try {
      if (!original) {
        const saved = await create.mutateAsync(createBody(draft));
        return onSaved(`Đã lưu bản nháp “${saved.title}”. Bổ sung nguồn, người và ngày rà soát rồi bấm Xuất bản.`);
      }
      const patch = changesOf(original, draft);
      if (!Object.keys(patch).length) return onSaved('Không có thay đổi nào để lưu.');
      const saved = await update.mutateAsync({ id: original.documentId, patch });
      onSaved(`Đã lưu “${saved.title}” (phiên bản ${saved.version}).`);
    } catch (e) {
      setFailure(toApiError(e));
    }
  };

  const remote = failure ? fieldErrorsOf(failure) : {};
  const shown: Errors = { ...errors, ...remote };
  const generic = failure && Object.keys(remote).length === 0 ? plainMessage(failure) : '';

  const body = (
    <>
      {original && (
        <p className="text-xs text-(--text-secondary)">
          {SOURCE_LABELS[original.sourceType]} · {STATUS_LABELS[original.status]} · phiên bản {original.version} · chỉ mục {INDEX_LABELS[original.indexStatus]} ({original.chunkCount} đoạn)
          {original.indexError ? ` · ${original.indexError}` : ''}
        </p>
      )}
      {readOnly && (
        <p role="note" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800">
          {original!.sourceType === 'CATALOG_PROTOCOL' ? 'Tài liệu sinh từ phác đồ tiêm, chỉ xem (BR-AI-13).' : 'Tài liệu đã lưu trữ không sửa được.'}
        </p>
      )}
      <Field label="Tiêu đề" error={shown.title} hint={`${draft.title.length}/${AI_LIMITS.title}`}>
        {(id) => <Input id={id} value={draft.title} maxLength={AI_LIMITS.title} disabled={readOnly} onChange={(e) => set({ title: e.target.value })} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Chủ đề" error={shown.topic}>
          {(id) => (
            <select id={id} className={selectClass} value={draft.topic} disabled={readOnly} onChange={(e) => set({ topic: e.target.value as DocumentTopic })}>
              <option value="">— Chọn —</option>
              {DOCUMENT_TOPICS.map((t) => (
                <option key={t} value={t}>
                  {TOPIC_LABELS[t]}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Loài áp dụng" error={shown.species}>
          {(id) => (
            <select id={id} className={selectClass} value={draft.species} disabled={readOnly} onChange={(e) => set({ species: e.target.value as DocumentSpecies })}>
              <option value="">— Chọn —</option>
              {DOCUMENT_SPECIES.map((s) => (
                <option key={s} value={s}>
                  {SPECIES_LABELS[s]}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      <Field label="Nội dung (Markdown)" error={shown.content} hint={`${draft.content.length}/${AI_LIMITS.content}`}>
        {(id) => <textarea id={id} className="input min-h-48 font-mono text-sm" rows={10} maxLength={AI_LIMITS.content} disabled={readOnly} value={draft.content} onChange={(e) => set({ content: e.target.value })} />}
      </Field>
      <Field label="Ghi chú nguồn (lớp 1)" error={shown.sourceNote} hint={`Tổ chức, tên tài liệu / mục tham khảo. ${draft.sourceNote.length}/${AI_LIMITS.sourceNote}`}>
        {(id) => <textarea id={id} className="input" rows={2} maxLength={AI_LIMITS.sourceNote} disabled={readOnly} value={draft.sourceNote} onChange={(e) => set({ sourceNote: e.target.value })} />}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Bác sĩ thú y rà soát (lớp 2)" error={shown.reviewerName}>
          {(id) => <Input id={id} value={draft.reviewerName} maxLength={AI_LIMITS.reviewerName} disabled={readOnly} onChange={(e) => set({ reviewerName: e.target.value })} />}
        </Field>
        <Field label="Ngày rà soát" error={shown.reviewedOn}>
          {(id) => <Input id={id} type="date" value={draft.reviewedOn} disabled={readOnly} onChange={(e) => set({ reviewedOn: e.target.value })} />}
        </Field>
      </div>
      {published && <p className="text-xs text-(--text-tertiary)">Đã xuất bản: bắt buộc đủ nguồn, người và ngày rà soát; sửa nội dung thì phải cập nhật lại người và ngày rà soát (BR-AI-14).</p>}
      <FormError message={generic} />
    </>
  );

  if (readOnly) {
    return (
      <Modal open onOpenChange={(open) => !open && onClose()} className="max-w-2xl">
        <ModalHeader onClose={onClose}>
          <ModalTitle>Xem tài liệu</ModalTitle>
        </ModalHeader>
        <ModalBody className="space-y-4">{body}</ModalBody>
        <ModalFooter>
          <Button variant="outline" onClick={onClose}>
            Đóng
          </Button>
        </ModalFooter>
      </Modal>
    );
  }
  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title={original ? 'Sửa tài liệu' : 'Tạo tài liệu nháp'}
      description={original ? `Tài liệu #${original.documentId}` : 'Lưu thành bản nháp; chatbot chỉ dùng tài liệu đã xuất bản.'}
      onSubmit={submit}
      submitText={original ? 'Lưu thay đổi' : 'Lưu nháp'}
      loading={create.isPending || update.isPending}
      size="xl"
    >
      {body}
    </FormModal>
  );
}
