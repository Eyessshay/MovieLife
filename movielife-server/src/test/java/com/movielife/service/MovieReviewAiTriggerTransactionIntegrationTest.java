package com.movielife.service;

import com.movielife.dto.CreateReviewDTO;
import com.movielife.dto.UserDTO;
import com.movielife.entity.User;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.exception.BusinessException;
import com.movielife.mapper.UserMapper;
import com.movielife.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "movielife.startup-check.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.redisson.spring.starter.RedissonAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        })
class MovieReviewAiTriggerTransactionIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(14_400_000_000L);

    @Autowired
    private ReviewService reviewService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private UserMapper userMapper;
    @MockBean
    private StringRedisTemplate redisTemplate;
    @MockBean
    private ReviewAiSummaryTrigger trigger;

    private long userId;
    private long movieId;

    @BeforeEach
    void setUp() {
        userId = IDS.incrementAndGet();
        movieId = IDS.incrementAndGet();
        jdbcTemplate.update("INSERT INTO tb_user(id, phone, nick_name) VALUES (?, ?, ?)",
                userId, "14" + userId, "Step 14.4 trigger user");
        jdbcTemplate.update("INSERT INTO tb_movie(id, title, status) VALUES (?, ?, 2)",
                movieId, "Step 14.4 trigger movie");
        UserDTO current = new UserDTO();
        current.setId(userId);
        UserHolder.saveUser(current);
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        jdbcTemplate.update("DELETE FROM tb_review WHERE user_id=? AND target_type='MOVIE' AND target_id=?",
                userId, movieId);
        jdbcTemplate.update("DELETE FROM tb_movie WHERE id=?", movieId);
        jdbcTemplate.update("DELETE FROM tb_user WHERE id=?", userId);
    }

    @Test
    void committedMovieReviewShouldTriggerAfterCommit() {
        User user = new User();
        user.setId(userId);
        user.setNickName("Step 14.4 trigger user");
        when(userMapper.selectById(userId)).thenReturn(user);

        reviewService.createReview(request());

        verify(trigger).afterReviewCommitted(AiSummaryTargetType.MOVIE, movieId);
    }

    @Test
    void rolledBackMovieReviewShouldNotTrigger() {
        when(userMapper.selectById(userId)).thenReturn(null);

        assertThatThrownBy(() -> reviewService.createReview(request()))
                .isInstanceOf(BusinessException.class);

        verify(trigger, never()).afterReviewCommitted(AiSummaryTargetType.MOVIE, movieId);
    }

    private CreateReviewDTO request() {
        CreateReviewDTO request = new CreateReviewDTO();
        request.setTargetType("MOVIE");
        request.setTargetId(movieId);
        request.setScore(new BigDecimal("4.5"));
        request.setContent("Step 14.4 transaction trigger review");
        request.setImages(Collections.emptyList());
        return request;
    }
}
