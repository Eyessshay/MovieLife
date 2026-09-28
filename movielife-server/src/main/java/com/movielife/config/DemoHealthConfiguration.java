package com.movielife.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Profile("demo")
public class DemoHealthConfiguration {

    @Bean
    public HealthIndicator applicationHealthIndicator() {
        return () -> Health.up().build();
    }

    @Bean
    public HealthIndicator mysqlHealthIndicator(JdbcTemplate jdbcTemplate) {
        return () -> {
            try {
                Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                return Integer.valueOf(1).equals(result) ? Health.up().build() : Health.down().build();
            } catch (Exception ignored) {
                return Health.down().build();
            }
        };
    }

    @Bean
    public HealthIndicator redisHealthIndicator(RedisConnectionFactory redisConnectionFactory) {
        return () -> {
            try (RedisConnection connection = redisConnectionFactory.getConnection()) {
                String pong = connection.ping();
                return "PONG".equalsIgnoreCase(pong) ? Health.up().build() : Health.down().build();
            } catch (Exception ignored) {
                return Health.down().build();
            }
        };
    }
}
