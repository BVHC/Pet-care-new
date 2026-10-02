import { describe, expect, it, vi } from 'vitest';
import { recentEvents, startTimer, track } from './telemetry';

const last = () => recentEvents()[recentEvents().length - 1];

describe('telemetry', () => {
  it('records an event with its properties and time', () => {
    track('payment', { total: 230_000 });
    expect(last()).toMatchObject({ name: 'payment', props: { total: 230_000 }, at: expect.any(Number) });
  });

  it('keeps only the latest 100 events', () => {
    for (let i = 0; i < 105; i++) track('board_move', { i });
    expect(recentEvents()).toHaveLength(100);
    expect(last()?.props).toEqual({ i: 104 });
  });

  it('measures how long a step took', () => {
    vi.spyOn(performance, 'now').mockReturnValueOnce(100).mockReturnValueOnce(350);
    const stop = startTimer('soap_save');
    stop();
    expect(last()).toMatchObject({ name: 'timing', props: { step: 'soap_save', ms: 250 } });
  });
});
