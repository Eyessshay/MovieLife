package com.movielife.config;

import com.movielife.mapper.MovieMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "movielife.startup-check", name = "enabled", havingValue = "true", matchIfMissing = true)
public class InfrastructureStartupCheck implements ApplicationRunner {

    private final MovieMapper movieMapper;
    private final RedisConnectionFactory redisConnectionFactory;

    @Override
    public void run(ApplicationArguments args) {
        movieMapper.selectById(1L);

        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            String pong = connection.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new IllegalStateException("Redis PING returned: " + pong);
            }
        }

        log.info("MovieLife infrastructure check passed: MySQL and Redis are available");
    }
}
