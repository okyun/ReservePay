package com.example.reservepay.checkout;

import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/**
 * Redis에서 당첨된 소수 요청만 DB에 반영할 때 동시성을 HikariCP 풀 크기(10)에 맞춘다.
 */
@Component
public class CheckoutDbGate {

    private static final int MAX_CONCURRENT_DB_CHECKOUTS = 10;

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_DB_CHECKOUTS);

    public <T> T execute(Callable<T> action) {
        try {
            permits.acquire();
            return action.call();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Checkout DB 처리가 중단되었습니다.", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Checkout DB 처리에 실패했습니다.", e);
        } finally {
            permits.release();
        }
    }
}
