import type { ComplaintRelatedType, ComplaintTopic, CreateComplaintRequest } from '@/shared/types/complaint';

export interface ComplaintDraft {
  topic: ComplaintTopic | '';
  branchId: string;
  relatedType: ComplaintRelatedType | '';
  relatedId: string;
  content: string;
}

export const EMPTY_COMPLAINT_DRAFT: ComplaintDraft = { topic: '', branchId: '', relatedType: '', relatedId: '', content: '' };

/**
 * Bản nháp → body hợp đồng, hoặc câu báo thiếu. Chỉ chặn phần thiếu hẳn; độ dài nội dung (10–2 000), chi nhánh bắt buộc,
 * đối tượng có thuộc khách không, giới hạn số khiếu nại đang mở đều do máy chủ kiểm (400 BR-DG-05).
 */
export function complaintRequestOf(d: ComplaintDraft, branchId: number | null): CreateComplaintRequest | string {
  if (!d.topic) return 'Chọn chủ đề khiếu nại.';
  if (!d.content.trim()) return 'Nhập nội dung khiếu nại.';
  if (d.relatedType && !/^[1-9]\d*$/.test(d.relatedId.trim())) return 'Nhập mã số của đối tượng liên quan (số nguyên dương).';
  return {
    topic: d.topic,
    ...(branchId != null && { branchId }),
    ...(d.relatedType && { relatedType: d.relatedType, relatedId: Number(d.relatedId) }),
    content: d.content.trim(),
  };
}
