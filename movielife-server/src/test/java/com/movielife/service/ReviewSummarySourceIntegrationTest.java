package com.movielife.service;

import com.movielife.ai.ReviewSummarySnapshot;
import com.movielife.enums.AiSummaryTargetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "movielife.startup-check.enabled=false")
@Transactional
class ReviewSummarySourceIntegrationTest {

    @Autowired
    private ReviewSummarySourceService sourceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldReadOneConsistentNewestFirstSanitizedMovieSnapshotFromMysql() {
        long movieId = insertMovie();
        LocalDateTime base = LocalDateTime.of(2026, 9, 19, 10, 0);
        long oldest = insertReview(insertUser("131"), movieId, "3.0", "较早", 1, base);
        long middle = insertReview(insertUser("132"), movieId, "4.5", "联系 13812345678", 1, base.plusMinutes(1));
        long newest = insertReview(insertUser("133"), movieId, "5.0", "邮箱 test@example.com", 1, base.plusMinutes(2));
        insertReview(insertUser("134"), movieId, "1.0", "已删除", 0, base.plusMinutes(3));

        ReviewSummarySnapshot snapshot = sourceService.loadSnapshot(AiSummaryTargetType.MOVIE, movieId);

        assertThat(snapshot.getSourceReviewCount()).isEqualTo(3);
        assertThat(snapshot.getReviews()).extracting(item -> item.getReviewId())
                .containsExactly(newest, middle, oldest);
        assertThat(snapshot.getReviews()).extracting(item -> item.getContent())
                .containsExactly("邮箱 [EMAIL]", "联系 [PHONE]", "较早");
        assertThat(snapshot.getReviews()).extracting(item -> item.getScore())
                .containsExactly("5", "4.5", "3");
    }

    private long insertMovie() {
        KeyHolder key = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_movie(title, status) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "AI snapshot integration movie");
            statement.setInt(2, 2);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private long insertUser(String suffix) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_user(phone, nick_name) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, "1999199" + suffix + System.nanoTime() % 1000);
            statement.setString(2, "AI summary source user");
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private long insertReview(long userId, long movieId, String score, String content,
                              int status, LocalDateTime updateTime) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO tb_review(user_id, target_type, target_id, score, content, status, create_time, update_time) "
                            + "VALUES (?, 'MOVIE', ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setLong(2, movieId);
            statement.setBigDecimal(3, new java.math.BigDecimal(score));
            statement.setString(4, content);
            statement.setInt(5, status);
            statement.setTimestamp(6, Timestamp.valueOf(updateTime));
            statement.setTimestamp(7, Timestamp.valueOf(updateTime));
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
