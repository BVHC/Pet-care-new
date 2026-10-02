import { beforeEach, describe, expect, it } from 'vitest';
import { posTotal, usePosStore } from './pos.store';

const pos = () => usePosStore.getState();

beforeEach(() => {
  pos().clear();
});

describe('POS cart (usePOS)', () => {
  it('adds a product twice as one line of two', () => {
    pos().addProduct('p-pate');
    pos().addProduct('p-pate');
    expect(pos().retail).toEqual([{ productId: 'p-pate', qty: 2 }]);
  });

  it('drops a line when its quantity reaches zero', () => {
    pos().addProduct('p-pate');
    pos().setQty('p-pate', 0);
    expect(pos().retail).toEqual([]);
  });

  it('combines pending orders of one customer (BR-TG-03)', () => {
    pos().attachOrder('order-1', 'c1');
    pos().attachOrder('order-4', 'c1');
    expect(pos()).toMatchObject({ customerId: 'c1', orderIds: ['order-1', 'order-4'] });
  });

  it("starts over when another customer's order is picked (BR-TG-03)", () => {
    pos().attachOrder('order-1', 'c1');
    pos().attachOrder('order-2', 'c2');
    expect(pos()).toMatchObject({ customerId: 'c2', orderIds: ['order-2'] });
  });

  it('ignores an order attached twice', () => {
    pos().attachOrder('order-1', 'c1');
    pos().attachOrder('order-1', 'c1');
    expect(pos().orderIds).toEqual(['order-1']);
  });

  it("detaches the previous customer's orders when the customer changes", () => {
    pos().attachOrder('order-1', 'c1');
    pos().setCustomer('c2');
    expect(pos()).toMatchObject({ customerId: 'c2', orderIds: [] });
  });

  it('resets everything after payment', () => {
    pos().addProduct('p-pate');
    pos().attachOrder('order-1', 'c1');
    pos().setMethod('BANK_TRANSFER');
    pos().clear();
    expect(pos()).toMatchObject({ retail: [], orderIds: [], customerId: null, method: 'CASH' });
  });

  it('totals counter items and attached orders only', () => {
    const total = posTotal(
      { retail: [{ productId: 'p-pate', qty: 3 }], orderIds: ['o1'] },
      [{ id: 'p-pate', price: 18_000 }],
      [
        { id: 'o1', total: 230_000 },
        { id: 'o2', total: 99_000 },
      ],
    );
    expect(total).toBe(284_000);
  });
});

describe('POS customer on the receipt', () => {
  it('forgets the customer once their last order leaves the receipt', () => {
    pos().attachOrder('order-1', 'c1');
    pos().detachOrder('order-1');
    expect(pos().customerId).toBeNull();
  });

  it("clears counter items when another customer's order is picked", () => {
    pos().attachOrder('order-1', 'c1');
    pos().addProduct('p-pate');
    pos().attachOrder('order-2', 'c2');
    expect(pos().retail).toEqual([]);
  });

  it('keeps counter items when the first order joins an anonymous sale', () => {
    pos().addProduct('p-pate');
    pos().attachOrder('order-1', 'c1');
    expect(pos().retail).toEqual([{ productId: 'p-pate', qty: 1 }]);
  });
});
