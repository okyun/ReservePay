// k6 공통 설정 — 환경변수로 덮어쓸 수 있음
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
export const PRODUCT_ID = __ENV.PRODUCT_ID || '1';
export const TOTAL_STOCK = parseInt(__ENV.TOTAL_STOCK || '10', 10);
export const MEMBER_ID_BASE = parseInt(__ENV.MEMBER_ID_BASE || '10000', 10);
export const MEMBER_POOL = parseInt(__ENV.MEMBER_POOL || '50000', 10);

export const THRESHOLDS = {
  http_req_failed: ['rate<0.01'],          // 5xx 비율 1% 미만
  http_req_duration: ['p(95)<500'],        // p95 500ms 미만
  checkout_wins: [`count==${TOTAL_STOCK}`], // 당첨 정확히 재고 수
};
