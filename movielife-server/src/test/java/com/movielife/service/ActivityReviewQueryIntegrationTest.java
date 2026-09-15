package com.movielife.service;

import com.movielife.dto.ReviewQueryDTO;
import com.movielife.entity.Activity;
import com.movielife.mapper.ActivityMapper;
import com.movielife.utils.UserHolder;
import com.movielife.vo.ReviewVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

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
class ActivityReviewQueryIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(13_400_000_000L);

    @Autowired
    private ReviewService reviewService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    private long activityId;
    private long firstReviewId;
    private long secondReviewId;
    private long thirdReviewId;
    private final long[] userIds = new long[3];

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        activityId = IDS.incrementAndGet();
        for (int index = 0; index < userIds.length; index++) {
            userIds[index] = IDS.incrementAndGet();
            jdbcTemplate.update(
                    "INSERT INTO tb_user (id, phone, nick_name) VALUES (?, ?, ?)",
                    userIds[index], "14" + userIds[index], "Activity reviewer " + index
            );
        }
        jdbcTemplate.update(
                "INSERT INTO tb_activity "
                        + "(id, title, type, city, description, price_min, price_max, "
                        + "avg_score, score_count, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                activityId, "Step 13.4 review query", "talkshow", "上海",
                "Activity Review query verification", new BigDecimal("99.00"),
                new BigDecimal("199.00"), new BigDecimal("4.67"), 3
        );
        firstReviewId = IDS.incrementAndGet();
        secondReviewId = IDS.incrementAndGet();
        thirdReviewId = IDS.incrementAndGet();
        insertReview(firstReviewId, userIds[0], "4.0", 5,
                LocalDateTime.of(2026, 9, 16, 10, 0));
        insertReview(secondReviewId, userIds[1], "5.0", 5,
                LocalDateTime.of(2026, 9, 16, 11, 0));
        insertReview(thirdReviewId, userIds[2], "5.0", 3,
                LocalDateTime.of(2026, 9, 16, 11, 0));
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
        jdbcTemplate.update(
                "DELETE FROM tb_review WHERE target_type = 'ACTIVITY' AND target_id = ?", activityId
        );
        jdbcTemplate.update("DELETE FROM tb_activity WHERE id = ?", activityId);
        for (long userId : userIds) {
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", userId);
        }
    }

    @Test
    void shouldApplyThreeStableSortsWithoutChangingStoredFacts() {
        assertThat(ids(reviewService.queryReviews(query("latest")).getList()))
                .containsExactly(thirdReviewId, secondReviewId, firstReviewId);
        assertThat(ids(reviewService.queryReviews(query("likeCount")).getList()))
                .containsExactly(secondReviewId, firstReviewId, thirdReviewId);
        assertThat(ids(reviewService.queryReviews(query("score")).getList()))
                .containsExactly(thirdReviewId, secondReviewId, firstReviewId);

        Activity activity = activityMapper.selectById(activityId);
        assertThat(activity.getAvgScore()).isEqualByComparingTo("4.67");
        assertThat(activity.getScoreCount()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_review WHERE target_type = 'ACTIVITY' AND target_id = ? "
                        + "AND status = 1", Long.class, activityId)).isEqualTo(3L);
        verifyNoInteractions(stringRedisTemplate);
    }

    private ReviewQueryDTO query(String sortBy) {
        ReviewQueryDTO query = new ReviewQueryDTO();
        query.setTargetType("ACTIVITY");
        query.setTargetId(activityId);
        query.setSortBy(sortBy);
        return query;
    }

    private void insertReview(long reviewId, long userId, String score, int likeCount,
                              LocalDateTime createTime) {
        jdbcTemplate.update(
                "INSERT INTO tb_review "
                        + "(id, user_id, target_type, target_id, score, content, images, "
                        + "like_count, comment_count, status, create_time, update_time) "
                        + "VALUES (?, ?, 'ACTIVITY', ?, ?, ?, '[]', ?, 0, 1, ?, ?)",
                reviewId, userId, activityId, new BigDecimal(score),
                "Activity Review " + reviewId, likeCount, createTime, createTime
        );
    }

    private List<Long> ids(List<ReviewVO> reviews) {
        return reviews.stream().map(ReviewVO::getId)
                .collect(java.util.stream.Collectors.toList());
    }
}
