import { beforeEach, describe, expect, it } from 'vitest';
import { usePosStore } from './pos.store';

const pos = () => usePosStore.getState();

beforeEach(() => {
  pos().clear();
});

// Phiếu thu của quầy (T35): khách và Order chờ thu theo id số của sales-v1, bán lẻ đi qua Order RETAIL (T34) nên không còn giỏ hàng riêng.
describe('POS receipt selection (usePOS)', () => {
  it('starts empty with cash selected', () => {
    expect(pos()).toMatchObject({ customerId: null, orderIds: [], method: 'CASH' });
    expect('retail' in pos()).toBe(false);
  });

  it('combines pending orders of one customer (BR-TG-03)', () => {
    pos().attachOrder(1, 7);
    pos().attachOrder(4, 7);
    expect(pos()).toMatchObject({ customerId: 7, orderIds: [1, 4] });
  });

  it("starts over when another customer's order is picked (BR-TG-03)", () => {
    pos().attachOrder(1, 7);
    pos().attachOrder(2, 9);
    expect(pos()).toMatchObject({ customerId: 9, orderIds: [2] });
  });

  it('ignores an order attached twice', () => {
    pos().attachOrder(1, 7);
    pos().attachOrder(1, 7);
    expect(pos().orderIds).toEqual([1]);
  });

  it('picking a customer in the panel starts with no order selected; picking the same customer keeps the selection', () => {
    pos().attachOrder(1, 7);
    pos().setCustomer(7);
    expect(pos().orderIds).toEqual([1]);
    pos().setCustomer(9);
    expect(pos()).toMatchObject({ customerId: 9, orderIds: [] });
  });

  it('replaces the selection (tick boxes) and keeps the customer when nothing is left ticked', () => {
    pos().attachOrder(1, 7);
    pos().setOrderIds([1, 4, 5]);
    expect(pos().orderIds).toEqual([1, 4, 5]);
    pos().setOrderIds([]);
    expect(pos()).toMatchObject({ customerId: 7, orderIds: [] });
  });

  it('remembers the payment method until the receipt is cleared', () => {
    pos().setMethod('TRANSFER');
    pos().attachOrder(1, 7);
    expect(pos().method).toBe('TRANSFER');
    pos().clear();
    expect(pos()).toMatchObject({ customerId: null, orderIds: [], method: 'CASH' });
  });
});
