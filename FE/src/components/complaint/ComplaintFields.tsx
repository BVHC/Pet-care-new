import { Field, selectClass } from '@/components/admin/form-ui';
import { Input } from '@/components/ui/input';
import type { PublicBranch } from '@/shared/types/branch';
import { COMPLAINT_LIMITS, COMPLAINT_RELATED_TYPES, COMPLAINT_TOPICS, type ComplaintRelatedType, type ComplaintTopic } from '@/shared/types/complaint';
import type { ComplaintDraft } from './draft';
import { COMPLAINT_RELATED_LABELS, COMPLAINT_TOPIC_LABELS } from './labels';

// Các ô của biểu mẫu gửi khiếu nại (BR-DG-05), dùng chung cho khách (UC85 #1) và lễ tân tạo thay (#7): chỉ khác ô chọn chi nhánh.

interface Props {
  draft: ComplaintDraft;
  onChange: (patch: Partial<ComplaintDraft>) => void;
  /** Có danh sách thì hiện ô chọn chi nhánh (khách); vắng thì chi nhánh do nơi gọi cố định (lễ tân). */
  branches?: PublicBranch[];
}

export function ComplaintFields({ draft, onChange, branches }: Props) {
  return (
    <>
      <Field label="Chủ đề">
        {(id) => (
          <select id={id} className={selectClass} value={draft.topic} onChange={(e) => onChange({ topic: e.target.value as ComplaintTopic | '' })}>
            <option value="">Chọn chủ đề</option>
            {COMPLAINT_TOPICS.map((t) => (
              <option key={t} value={t}>
                {COMPLAINT_TOPIC_LABELS[t]}
              </option>
            ))}
          </select>
        )}
      </Field>
      {branches && (
        <Field label="Chi nhánh" hint="Bắt buộc trừ chủ đề “Vấn đề khác”.">
          {(id) => (
            <select id={id} className={selectClass} value={draft.branchId} onChange={(e) => onChange({ branchId: e.target.value })}>
              <option value="">Không chọn</option>
              {branches.map((b) => (
                <option key={b.branchId} value={b.branchId}>
                  {b.name}
                </option>
              ))}
            </select>
          )}
        </Field>
      )}
      <div className="grid grid-cols-2 gap-3">
        <Field label="Liên quan đến (tùy chọn)">
          {(id) => (
            <select
              id={id}
              className={selectClass}
              value={draft.relatedType}
              onChange={(e) => onChange({ relatedType: e.target.value as ComplaintRelatedType | '', relatedId: '' })}
            >
              <option value="">Không có</option>
              {COMPLAINT_RELATED_TYPES.map((t) => (
                <option key={t} value={t}>
                  {COMPLAINT_RELATED_LABELS[t]}
                </option>
              ))}
            </select>
          )}
        </Field>
        {draft.relatedType && (
          <Field label="Mã số" hint="Mã trên đơn / lượt / đặt chỗ của khách.">
            {(id) => (
              <Input id={id} inputMode="numeric" value={draft.relatedId} onChange={(e) => onChange({ relatedId: e.target.value })} />
            )}
          </Field>
        )}
      </div>
      <Field label="Nội dung" hint={`${draft.content.length}/${COMPLAINT_LIMITS.content}`}>
        {(id) => (
          <textarea
            id={id}
            rows={5}
            maxLength={COMPLAINT_LIMITS.content}
            className="w-full rounded-lg border border-(--color-border-default) bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent"
            placeholder="Mô tả điều chưa hài lòng: chuyện gì xảy ra, khi nào, bạn mong muốn được giải quyết thế nào."
            value={draft.content}
            onChange={(e) => onChange({ content: e.target.value })}
          />
        )}
      </Field>
    </>
  );
}
