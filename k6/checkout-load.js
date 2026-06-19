/**
 * ReservePay Checkout 부하 테스트 — 00시 트래픽 급증 시뮬레이션
 *
 * 시나리오: 평시 50 TPS → 30초 만에 1,000 TPS 피크 → 1분 유지 → 완화
 * 검증: WIN 정확히 10건, p95 < 500ms, 5xx < 1%
 */
import { runCheckout } from './lib/checkout.js';
import { THRESHOLDS } from './lib/config.js';

export const options = {
  scenarios: {
    midnight_spike: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 300,
      maxVUs: 1500,
      stages: [
        { duration: '30s', target: 50 },   // 평시
        { duration: '30s', target: 1000 }, // 00시 급증 (500~1000 TPS)
        { duration: '1m', target: 1000 },  // 피크 유지 (~1분)
        { duration: '20s', target: 50 },   // 완화
      ],
    },
  },
  thresholds: THRESHOLDS,
};

export default function () {
  runCheckout();
}
