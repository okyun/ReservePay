/**
 * 대규모 Checkout 부하 테스트 — 10만+ 요청
 *
 * 시나리오: 50 TPS → 1,000 TPS 피크 3분 → 총 10만 건 이상
 */
import { runCheckout } from './lib/checkout.js';
import { THRESHOLDS } from './lib/config.js';

export const options = {
  scenarios: {
    midnight_spike_100k: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 500,
      maxVUs: 2500,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '30s', target: 1000 },
        { duration: '3m', target: 1000 },
        { duration: '30s', target: 50 },
      ],
    },
  },
  thresholds: THRESHOLDS,
};

export default function () {
  runCheckout();
}
