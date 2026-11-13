import { useRef, useState } from 'react';
import { Input } from '../../../components/ui/input';
import { FormModal } from '../../../components/ui/modal-templates';
import { Field, FormError, selectClass } from '../../../components/admin/form-ui';
import { aiApi } from '../../../shared/api/ai.api';
import { fieldErrorsOf, plainMessage, toApiError, type ApiError } from '../../../shared/api/api-error';
import { useAiMutation } from '../../../shared/hooks/useAi';
import {
  AI_LIMITS,
  AI_IMPORT_EXTENSIONS,
  AI_IMPORT_MAX_BYTES,
  DOCUMENT_SPECIES,
  DOCUMENT_TOPICS,
  type DocumentSpecies,
  type DocumentTopic,
} from '../../../shared/types/ai';
import { SPECIES_LABELS, TOPIC_LABELS } from './labels';

/** base64 của file (không kèm tiền tố data:), đọc theo khối để không tràn tham số của `String.fromCharCode`. */
async function fileToBase64(file: File): Promise<string> {
  const buffer = await new Promise<ArrayBuffer>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(file);
  });
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

const extensionOf = (name: string) => name.slice(name.lastIndexOf('.') + 1).toLowerCase();

/** UC93 (ai-v1 #14, BR-AI-29): nạp `.md`, `.txt`, `.docx`, `.pdf` thành tài liệu NHÁP. Không xuất bản: vẫn phải điền nguồn, người và ngày rà soát. */
export function ImportDocumentDialog({ onClose, onDone }: { onClose: () => void; onDone: (text: string) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [topic, setTopic] = useState<DocumentTopic | ''>('');
  const [species, setSpecies] = useState<DocumentSpecies | ''>('');
  const [sourceNote, setSourceNote] = useState('');
  const [errors, setErrors] = useState<Partial<Record<'file' | 'topic' | 'species', string>>>({});
  const [failure, setFailure] = useState<ApiError | null>(null);
  const importFile = useAiMutation(aiApi.importDocument);

  const pick = (f: File | null) => {
    setFile(f);
    setErrors((e) => ({ ...e, file: undefined }));
  };

  const submit = async () => {
    setFailure(null);
    const found: typeof errors = {};
    if (!file) found.file = 'Chọn một file.';
    else if (!AI_IMPORT_EXTENSIONS.includes(extensionOf(file.name))) found.file = 'Chỉ nhận file .md, .txt, .docx, .pdf.';
    else if (file.size > AI_IMPORT_MAX_BYTES) found.file = 'File vượt quá 2 MB.';
    else if (file.size === 0) found.file = 'File rỗng.';
    if (!topic) found.topic = 'Chọn chủ đề.';
    if (!species) found.species = 'Chọn loài áp dụng.';
    setErrors(found);
    if (Object.keys(found).length) return;
    try {
      const result = await importFile.mutateAsync({
        fileName: file!.name,
        contentBase64: await fileToBase64(file!),
        topic: topic as DocumentTopic,
        species: species as DocumentSpecies,
        ...(sourceNote.trim() ? { sourceNote: sourceNote.trim() } : {}),
      });
      const n = result.documents.length;
      onDone(`Đã nạp “${file!.name}” thành ${n} bản nháp${n > 1 ? ' (file dài, tách theo mục)' : ''}. Bổ sung nguồn, người và ngày rà soát rồi bấm Xuất bản.`);
    } catch (e) {
      setFailure(toApiError(e));
    }
  };

  const remote = failure ? fieldErrorsOf(failure) : {};
  const generic = failure && Object.keys(remote).length === 0 ? plainMessage(failure) : '';

  return (
    <FormModal
      open
      onOpenChange={(open) => !open && onClose()}
      title="Nạp tài liệu từ file"
      description="Chấp nhận .md, .txt, .docx, .pdf (tối đa 2 MB). Tài liệu tạo ra là bản nháp, chưa dùng cho chatbot."
      submitText="Nạp file"
      loading={importFile.isPending}
      onSubmit={submit}
      size="lg"
    >
      <Field label="File" error={errors.file ?? remote.fileName ?? remote.contentBase64} hint={file ? `${file.name} · ${Math.max(1, Math.round(file.size / 1024))} kB` : 'Chưa chọn file'}>
        {(id) => (
          <input
            id={id}
            ref={input}
            type="file"
            accept=".md,.txt,.docx,.pdf"
            className="input"
            onChange={(e) => pick(e.target.files?.[0] ?? null)}
          />
        )}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Chủ đề" error={errors.topic ?? remote.topic}>
          {(id) => (
            <select id={id} className={selectClass} value={topic} onChange={(e) => setTopic(e.target.value as DocumentTopic)}>
              <option value="">— Chọn —</option>
              {DOCUMENT_TOPICS.map((t) => (
                <option key={t} value={t}>
                  {TOPIC_LABELS[t]}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Loài áp dụng" error={errors.species ?? remote.species}>
          {(id) => (
            <select id={id} className={selectClass} value={species} onChange={(e) => setSpecies(e.target.value as DocumentSpecies)}>
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
      <Field label="Ghi chú nguồn (lớp 1, tùy chọn)" error={remote.sourceNote} hint={`Bỏ trống thì ghi “Nạp từ file …”. ${sourceNote.length}/${AI_LIMITS.sourceNote}`}>
        {(id) => <Input id={id} value={sourceNote} maxLength={AI_LIMITS.sourceNote} onChange={(e) => setSourceNote(e.target.value)} />}
      </Field>
      <FormError message={generic} />
    </FormModal>
  );
}
