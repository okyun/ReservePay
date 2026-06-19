import { check } from 'k6';
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, PRODUCT_ID, MEMBER_ID_BASE, MEMBER_POOL } from './config.js';

export const checkoutWins = new Counter('checkout_wins');
export const checkoutSoldOut = new Counter('checkout_sold_out');
export const checkoutFailure = new Counter('checkout_failure');
export const checkoutDuplicate = new Counter('checkout_duplicate');
export const checkoutNotStarted = new Counter('checkout_not_started');
export const checkoutServerError = new Counter('checkout_server_error');
export const checkoutOther = new Counter('checkout_other');
export const checkoutDuration = new Trend('checkout_duration', true);

/**
 * 요청마다 넓은 범위에서 무작위 memberId를 뽑아 선착순 공정성을 검증한다.
 * VU·iteration 조합으로 재요청 시에도 중복을 피한다.
 */
export function uniqueMemberId() {
  const randomOffset = Math.floor(Math.random() * MEMBER_POOL);
  const serial = __VU * 1_000_000 + __ITER;
  return MEMBER_ID_BASE + ((randomOffset + serial) % MEMBER_POOL);
}

export function runCheckout() {
  const memberId = uniqueMemberId();
  const url = `${BASE_URL}/api/checkout?productId=${PRODUCT_ID}&memberId=${memberId}`;
  const res = http.get(url, { tags: { name: 'checkout' } });
  checkoutDuration.add(res.timings.duration);

  let outcome = 'OTHER';

  if (res.status === 200) {
    const body = res.json();
    if (body && body.success === true) {
      outcome = 'WIN';
      checkoutWins.add(1);
    } else if (body && body.message === '판매가 종료되었습니다.') {
      outcome = 'SOLD_OUT';
      checkoutSoldOut.add(1);
    } else if (body && body.message === '예약에 실패하셨습니다.') {
      outcome = 'FAILURE';
      checkoutFailure.add(1);
    } else {
      checkoutOther.add(1);
    }
  } else if (res.status === 409) {
    outcome = 'DUPLICATE';
    checkoutDuplicate.add(1);
  } else if (res.status === 403) {
    outcome = 'NOT_STARTED';
    checkoutNotStarted.add(1);
  } else if (res.status >= 500) {
    outcome = 'SERVER_ERROR';
    checkoutServerError.add(1);
  } else {
    checkoutOther.add(1);
  }

  check(res, {
    'status is 2xx/4xx (no 5xx)': (r) => r.status < 500,
  });

  return { outcome, memberId, status: res.status };
}
