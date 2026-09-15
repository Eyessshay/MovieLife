package com.movielife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.dto.CreateReviewDTO;
import com.movielife.dto.UpdateReviewDTO;
import com.movielife.entity.Movie;
import com.movielife.entity.Review;
import com.movielife.entity.User;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.ReviewMapper;
import com.movielife.mapper.UserMapper;
import com.movielife.service.impl.ReviewServiceImpl;
import com.movielife.utils.UserHolder;
import com.movielife.vo.ReviewLikeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceImplTest {

    @Mock
    private ReviewMapper reviewMapper;
    @Mock
    private MovieMapper movieMapper;
    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private ReviewServiceImpl reviewService;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewServiceImpl(
                reviewMapper, movieMapper, activityMapper, userMapper, redisTemplate, new ObjectMapper()
        );
        UserHolder.saveUser(userContext(1L));
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void createShouldInsertReviewAndAtomicallyAddMovieScore() {
        User user = new User();
        user.setId(1L);
        user.setNickName("测试用户");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(movieMapper.selectById(1L)).thenReturn(movie());
        when(reviewMapper.selectByUserAndTargetForUpdate(1L, "MOVIE", 1L)).thenReturn(null);
        doAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            review.setId(2L);
            return 1;
        }).when(reviewMapper).insert(any(Review.class));
        when(movieMapper.addReviewScore(1L, new BigDecimal("4.0"))).thenReturn(1);

        assertThat(reviewService.createReview(createRequest("4.0")).getId()).isEqualTo(2L);

        verify(movieMapper).addReviewScore(1L, new BigDecimal("4.0"));
        verify(redisTemplate).delete("movie:detail:1");
    }

    @Test
    void activeReviewShouldBeRejectedAsDuplicate() {
        when(movieMapper.selectById(1L)).thenReturn(movie());
        Review existing = review(2L, 1L, "4.0", 1);
        when(reviewMapper.selectByUserAndTargetForUpdate(1L, "MOVIE", 1L)).thenReturn(existing);

        assertBusinessError(() -> reviewService.createReview(createRequest("4.0")),
                ErrorCode.DUPLICATE_REVIEW);
        verify(movieMapper, never()).addReviewScore(anyLong(), any(BigDecimal.class));
    }

    @Test
    void invalidScoreRangeAndStepShouldHaveDifferentErrors() {
        assertBusinessError(() -> reviewService.createReview(createRequest("6.0")),
                ErrorCode.INVALID_SCORE);
        assertBusinessError(() -> reviewService.createReview(createRequest("4.3")),
                ErrorCode.INVALID_SCORE_STEP);
    }

    @Test
    void updateByAnotherUserShouldBeForbidden() {
        Review existing = review(2L, 2L, "4.0", 1);
        when(reviewMapper.selectByIdForUpdate(2L)).thenReturn(existing);
        UpdateReviewDTO request = new UpdateReviewDTO();
        request.setScore(new BigDecimal("5.0"));
        request.setContent("修改内容");
        request.setImages(Collections.emptyList());

        assertBusinessError(() -> reviewService.updateReview(2L, request),
                ErrorCode.REVIEW_FORBIDDEN);
        verify(movieMapper, never()).replaceReviewScore(anyLong(), any(), any());
    }

    @Test
    void repeatedDeleteOfSoftDeletedReviewShouldNotChangeAggregateAgain() {
        when(reviewMapper.selectByIdForUpdate(2L)).thenReturn(review(2L, 1L, "4.0", 0));

        reviewService.deleteReview(2L);

        verify(reviewMapper, never()).softDelete(anyLong(), any());
        verify(movieMapper, never()).removeReviewScore(anyLong(), any());
    }

    @Test
    void repeatedLikeShouldNotIncrementDatabaseCountAgain() {
        Review before = review(2L, 1L, "4.0", 1);
        before.setLikeCount(1);
        when(reviewMapper.selectById(2L)).thenReturn(before);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.addIfAbsent(eq("review:like:2"), eq("1"), any(Double.class)))
                .thenReturn(false);

        ReviewLikeVO result = reviewService.likeReview(2L);

        assertThat(result.getLiked()).isTrue();
        assertThat(result.getLikeCount()).isEqualTo(1);
        verify(reviewMapper, never()).incrementLikeCount(anyLong());
    }

    @Test
    void deleteShouldRetryReviewLikeCacheCleanup() {
        Review existing = review(2L, 1L, "4.0", 1);
        when(reviewMapper.selectByIdForUpdate(2L)).thenReturn(existing);
        when(reviewMapper.softDelete(eq(2L), any())).thenReturn(1);
        when(movieMapper.removeReviewScore(1L, new BigDecimal("4.0"))).thenReturn(1);
        when(redisTemplate.delete("movie:detail:1")).thenReturn(true);
        when(redisTemplate.delete("review:like:2"))
                .thenThrow(new RuntimeException("redis unavailable"))
                .thenThrow(new RuntimeException("redis unavailable"))
                .thenReturn(true);

        reviewService.deleteReview(2L);

        verify(redisTemplate, times(3)).delete("review:like:2");
    }

    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }

    private CreateReviewDTO createRequest(String score) {
        CreateReviewDTO request = new CreateReviewDTO();
        request.setTargetType("MOVIE");
        request.setTargetId(1L);
        request.setScore(new BigDecimal(score));
        request.setContent("测试评价");
        request.setImages(Collections.emptyList());
        return request;
    }

    private Movie movie() {
        Movie movie = new Movie();
        movie.setId(1L);
        return movie;
    }

    private Review review(Long id, Long userId, String score, int status) {
        Review review = new Review();
        review.setId(id);
        review.setUserId(userId);
        review.setTargetType("MOVIE");
        review.setTargetId(1L);
        review.setScore(new BigDecimal(score));
        review.setContent("测试评价");
        review.setImages("[]");
        review.setLikeCount(0);
        review.setCommentCount(0);
        review.setStatus(status);
        return review;
    }

    private com.movielife.dto.UserDTO userContext(Long id) {
        com.movielife.dto.UserDTO user = new com.movielife.dto.UserDTO();
        user.setId(id);
        return user;
    }
}
