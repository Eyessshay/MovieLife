package com.movielife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.movielife.dto.CreateReviewDTO;
import com.movielife.dto.UserDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Review;
import com.movielife.entity.User;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.ReviewMapper;
import com.movielife.mapper.UserMapper;
import com.movielife.utils.UserHolder;
import com.movielife.vo.ReviewVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "movielife.startup-check.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.redisson.spring.starter.RedissonAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
class ActivityReviewWriteTransactionIntegrationTest {

    private static final String FAILURE_TRIGGER = "trg_step_13_3_activity_aggregate_failure";
    private static final AtomicLong IDS = new AtomicLong(13_300_000_000L);

    @Autowired
    private ReviewService reviewService;
    @Autowired
    private ReviewMapper reviewMapper;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private UserMapper userMapper;
    @MockBean
    private StringRedisTemplate stringRedisTemplate;
    @MockBean
    private ReviewAiSummaryTrigger reviewAiSummaryTrigger;

    private long userId;
    private long activityId;

    @BeforeEach
    void setUp() {
        userId = IDS.incrementAndGet();
        activityId = IDS.incrementAndGet();
        jdbcTemplate.update(
                "INSERT INTO tb_user (id, phone, nick_name) VALUES (?, ?, ?)",
                userId, "13" + userId, "Step 13.3 transaction user"
        );
        jdbcTemplate.update(
                "INSERT INTO tb_activity "
                        + "(id, title, type, city, description, price_min, price_max, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                activityId, "Step 13.3 transaction activity", "talkshow", "上海",
                "Activity Review transaction verification", new BigDecimal("99.00"),
                new BigDecimal("199.00")
        );
        UserDTO currentUser = new UserDTO();
        currentUser.setId(userId);
        UserHolder.saveUser(currentUser);
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + FAILURE_TRIGGER);
        jdbcTemplate.update(
                "DELETE FROM tb_review WHERE user_id = ? AND target_type = 'ACTIVITY' AND target_id = ?",
                userId, activityId
        );
        jdbcTemplate.update("DELETE FROM tb_activity WHERE id = ?", activityId);
        jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", userId);
    }

    @Test
    void shouldRollbackInsertedReviewWhenActivityAggregateUpdateFails() {
        jdbcTemplate.execute(
                "CREATE TRIGGER " + FAILURE_TRIGGER + " AFTER INSERT ON tb_review "
                        + "FOR EACH ROW UPDATE tb_activity SET status = 2 "
                        + "WHERE id = NEW.target_id AND NEW.target_type = 'ACTIVITY' "
                        + "AND NEW.target_id = " + activityId
        );

        assertBusinessError(() -> reviewService.createReview(createRequest()), ErrorCode.INTERNAL_ERROR);

        assertThat(findReview()).isNull();
        assertAggregate("0.00", 0, 1);
        verify(stringRedisTemplate, never()).delete(anyString());
        verify(reviewAiSummaryTrigger, never()).afterReviewCommitted(
                AiSummaryTargetType.ACTIVITY, activityId);
    }

    @Test
    void shouldCompleteCreateDuplicateRestoreUpdateAndIdempotentDeleteLifecycle() {
        User user = new User();
        user.setId(userId);
        user.setNickName("Step 13.3 transaction user");
        when(userMapper.selectById(userId)).thenReturn(user);
        when(stringRedisTemplate.opsForZSet()).thenReturn(mock(ZSetOperations.class));

        ReviewVO created = reviewService.createReview(createRequest());
        assertAggregate("4.50", 1, 1);
        assertBusinessError(() -> reviewService.createReview(createRequest()),
                ErrorCode.DUPLICATE_REVIEW);
        assertAggregate("4.50", 1, 1);

        reviewService.deleteReview(created.getId());
        reviewService.deleteReview(created.getId());
        assertThat(reviewMapper.selectById(created.getId()).getStatus()).isZero();
        assertAggregate("0.00", 0, 1);

        jdbcTemplate.update("UPDATE tb_review SET like_count = 7 WHERE id = ?", created.getId());
        jdbcTemplate.update("UPDATE tb_activity SET status = 2 WHERE id = ?", activityId);
        assertBusinessError(() -> reviewService.createReview(createRequest()),
                ErrorCode.ACTIVITY_NOT_FOUND);

        jdbcTemplate.update("UPDATE tb_activity SET status = 1 WHERE id = ?", activityId);
        CreateReviewDTO restoreRequest = createRequest();
        restoreRequest.setScore(new BigDecimal("3.5"));
        ReviewVO restored = reviewService.createReview(restoreRequest);
        assertThat(restored.getId()).isEqualTo(created.getId());
        assertThat(reviewMapper.selectById(created.getId()).getLikeCount()).isZero();
        assertAggregate("3.50", 1, 1);

        jdbcTemplate.update("UPDATE tb_activity SET status = 2 WHERE id = ?", activityId);
        com.movielife.dto.UpdateReviewDTO update = new com.movielife.dto.UpdateReviewDTO();
        update.setScore(new BigDecimal("5.0"));
        update.setContent("Updated after Activity offline");
        update.setImages(Collections.emptyList());
        reviewService.updateReview(created.getId(), update);
        assertAggregate("5.00", 1, 2);

        reviewService.deleteReview(created.getId());
        assertAggregate("0.00", 0, 2);
        verify(stringRedisTemplate, atLeastOnce()).delete("review:like:" + created.getId());
        verify(reviewAiSummaryTrigger, times(5)).afterReviewCommitted(
                AiSummaryTargetType.ACTIVITY, activityId);
    }

    @Test
    void shouldRollbackReviewAndAggregateWhenLaterTransactionalWorkFails() {
        assertBusinessError(() -> reviewService.createReview(createRequest()), ErrorCode.USER_NOT_FOUND);

        assertThat(findReview()).isNull();
        assertAggregate("0.00", 0, 1);
        verify(stringRedisTemplate, never()).delete(anyString());
        verify(reviewAiSummaryTrigger, never()).afterReviewCommitted(
                AiSummaryTargetType.ACTIVITY, activityId);
    }

    private Review findReview() {
        return reviewMapper.selectOne(new LambdaQueryWrapper<Review>()
                .eq(Review::getUserId, userId)
                .eq(Review::getTargetType, "ACTIVITY")
                .eq(Review::getTargetId, activityId));
    }

    private void assertAggregate(String avgScore, int scoreCount, int status) {
        Activity activity = activityMapper.selectById(activityId);
        assertThat(activity).isNotNull();
        assertThat(activity.getAvgScore()).isEqualByComparingTo(avgScore);
        assertThat(activity.getScoreCount()).isEqualTo(scoreCount);
        assertThat(activity.getStatus()).isEqualTo(status);
    }

    private CreateReviewDTO createRequest() {
        CreateReviewDTO request = new CreateReviewDTO();
        request.setTargetType("ACTIVITY");
        request.setTargetId(activityId);
        request.setScore(new BigDecimal("4.5"));
        request.setContent("Step 13.3 transaction review");
        request.setImages(Collections.emptyList());
        return request;
    }

    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
