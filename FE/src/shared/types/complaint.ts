// Khiếu nại (UC85, UC86) theo docs/api/complaint-v1.md #1–12 (mục Schema + openapi/complaint-v1.yaml). Trường `?` có thể vắng hoặc null.

/** 03 #26. */
export const COMPLAINT_STATUSES = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'] as const;
export type ComplaintStatus = (typeof COMPLAINT_STATUSES)[number];

export const COMPLAINT_TOPICS = ['SERVICE_QUALITY', 'GROOMING', 'BOARDING', 'PURCHASE', 'STAFF', 'OTHER'] as const;
export type ComplaintTopic = (typeof COMPLAINT_TOPICS)[number];

export const COMPLAINT_RELATED_TYPES = ['ORDER', 'ONLINE_ORDER', 'VISIT', 'BOARDING'] as const;
export type ComplaintRelatedType = (typeof COMPLAINT_RELATED_TYPES)[number];

/** `WITHDRAWN` = khách rút khi còn xử lý (không mở lại); `AUTO` = hệ thống tự đóng (ST17). */
export const COMPLAINT_CLOSED_REASONS = ['CUSTOMER', 'WITHDRAWN', 'AUTO'] as const;
export type ComplaintClosedReason = (typeof COMPLAINT_CLOSED_REASONS)[number];

export type ComplaintMessageAuthorType = 'CUSTOMER' | 'STAFF' | 'SYSTEM';
/** `STATUS` = dòng vết chuyển trạng thái do hệ thống ghi. */
export type ComplaintMessageKind = 'MESSAGE' | 'STATUS';

/** Độ dài của BR-DG-05 (nội dung 10–2 000) và maxLength của hợp đồng. */
export const COMPLAINT_LIMITS = { contentMin: 10, content: 2000, body: 2000, resolution: 2000 } as const;

export interface CreateComplaintRequest {
  topic: ComplaintTopic;
  /** Bắt buộc trừ chủ đề `OTHER` (BR-DG-05). */
  branchId?: number | null;
  /** Cùng có hoặc cùng vắng với `relatedId`; phải thuộc khách. */
  relatedType?: ComplaintRelatedType | null;
  relatedId?: number | null;
  content: string;
}

/** Lễ tân tạo thay khách. */
export interface CreateComplaintForCustomerRequest extends CreateComplaintRequest {
  customerId: number;
}

export interface ComplaintSummary {
  complaintId: number;
  code: string;
  topic: ComplaintTopic;
  status: ComplaintStatus;
  branchId?: number | null;
  escalated: boolean;
  lastActivityAt: string;
  createdAt: string;
}

export interface ComplaintMessage {
  messageId: number;
  authorType: ComplaintMessageAuthorType;
  authorName?: string | null;
  kind: ComplaintMessageKind;
  body: string;
  createdAt: string;
}

export interface Complaint {
  complaintId: number;
  code: string;
  customerId: number;
  customerName: string;
  branchId?: number | null;
  topic: ComplaintTopic;
  relatedType?: ComplaintRelatedType | null;
  relatedId?: number | null;
  content: string;
  status: ComplaintStatus;
  /** Lễ tân tạo thay khách. */
  createdByStaff: boolean;
  handlerName?: string | null;
  resolution?: string | null;
  resolvedAt?: string | null;
  closedAt?: string | null;
  closedReason?: ComplaintClosedReason | null;
  reopenCount: number;
  escalatedAt?: string | null;
  lastActivityAt: string;
  createdAt: string;
  /** Cũ nhất trước. */
  messages: ComplaintMessage[];
}

/** Tin nhắn trao đổi; cũng là lý do mở lại (`reopen`). */
export interface PostMessageRequest {
  body: string;
}

export interface ResolveComplaintRequest {
  resolution: string;
}

/** Danh sách của khách: `status` vắng = mọi trạng thái. */
export interface MyComplaintQuery {
  status?: ComplaintStatus;
  page?: number;
  size?: number;
}

/** Danh sách xử lý: `status` vắng = OPEN + IN_PROGRESS; `escalated=true` chỉ lấy khiếu nại đã leo thang (quá hạn). */
export interface ComplaintQuery extends MyComplaintQuery {
  escalated?: boolean;
}
