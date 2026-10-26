import { describe, expect, it } from 'vitest';
import { makeVisit } from '../../test/visit-harness';
import type { Visit } from '../types/visit';
import { moveRejection } from './useKanban';

const ME = 6;
const card = (over: Partial<Visit> = {}): Visit =>
  makeVisit(7, { petName: 'Rocky', serviceGroup: 'GROOMING', assigneeId: ME, assigneeName: 'Thu Hà', ...over });
const colleague = { assigneeId: 8, assigneeName: 'Gia Huy' };

describe('moveRejection (thẻ grooming theo lượt của API)', () => {
  it('lets me start my waiting card', () => {
    expect(moveRejection(card(), 'IN_PROGRESS', ME)).toBeNull();
  });

  it('"Xong" của chính mình khi đang làm là hoàn tất lượt (visit-v1 #10): được phép', () => {
    expect(moveRejection(card({ status: 'IN_PROGRESS' }), 'READY', ME)).toBeNull();
  });

  it('thẻ đã xong (COMPLETED) không quay lại cột trước (BR-TN-09), thả lại đúng cột "Xong" không phải lỗi', () => {
    const done = card({ status: 'COMPLETED' });
    expect(moveRejection(done, 'WAITING', ME)?.code).toBe('BR-TN-09');
    expect(moveRejection(done, 'IN_PROGRESS', ME)?.code).toBe('BR-TN-09');
    expect(moveRejection(done, 'READY', ME)?.code).toBe('NO_MOVE');
  });

  it('never moves a card backwards (BR-TN-09)', () => {
    expect(moveRejection(card({ status: 'IN_PROGRESS' }), 'WAITING', ME)?.code).toBe('BR-TN-09');
  });

  it('does not skip "Đang làm"', () => {
    expect(moveRejection(card(), 'READY', ME)?.code).toBe('BR-TN-09');
  });

  it("refuses to start a colleague's card (BR-TN-05)", () => {
    expect(moveRejection(card(colleague), 'IN_PROGRESS', ME)?.code).toBe('BR-TN-05');
  });

  it('refuses to start a card nobody has been assigned (BR-TN-05, BR-TN-09: không gọi lượt chưa gán)', () => {
    const rejection = moveRejection(card({ assigneeId: null, assigneeName: null }), 'IN_PROGRESS', ME);
    expect(rejection?.code).toBe('BR-TN-05');
    expect(rejection?.message).toMatch(/chưa được gán/);
  });

  it("refuses to finish a colleague's card", () => {
    const theirs = card({ status: 'IN_PROGRESS', ...colleague });
    expect(moveRejection(theirs, 'READY', ME)?.code).toBe('ACCESS_DENIED_SCOPE');
  });

  it('thả lại đúng cột đang đứng thì không phải lỗi, chỉ là không làm gì', () => {
    expect(moveRejection(card(), 'WAITING', ME)?.code).toBe('NO_MOVE');
    expect(moveRejection(card({ status: 'IN_PROGRESS' }), 'IN_PROGRESS', ME)?.code).toBe('NO_MOVE');
  });
});
