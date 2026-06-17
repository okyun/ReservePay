package com.example.reservepay.redis;

import io.lettuce.core.ClientOptions;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Lettuce는 기본적으로 연결이 끊긴 동안 들어온 명령을 큐에 쌓아뒀다가 재연결되면 그제서야
     * 실행한다(disconnectedBehavior=DEFAULT). 이러면 우리 코드가 타임아웃으로 "실패"라고 판단해
     * 재시도를 포기하고 응답까지 보낸 뒤에, 큐에 쌓여 있던 명령이 Redis 복구 시점에 뒤늦게
     * 실행되어 재고가 차감되는 "유령 실행"이 생긴다(Checkout 재시도 로직의 release 보장이
     * 깨짐). REJECT_COMMANDS로 바꿔 연결이 끊긴 즉시 명령을 거부하게 한다 — 그래야 우리 코드가
     * 본 예외가 "그 명령이 진짜 실행되지 않았다"는 뜻이 되어, release 여부 판단을 신뢰할 수 있다.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceClientConfigurationBuilderCustomizer() {
        return builder -> builder.clientOptions(
                ClientOptions.builder()
                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                        .autoReconnect(true)
                        .build()
        );
    }

    @Bean
    public RedisScript<Long> stockDecrScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/stock_decr.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
