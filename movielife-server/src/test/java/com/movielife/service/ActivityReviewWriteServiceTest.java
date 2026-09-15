package com.movielife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.dto.CreateReviewDTO;
import com.movielife.dto.UpdateReviewDTO;
import com.movielife.entity.Activity;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityReviewWriteServiceTest {

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
                reviewMapper, movieMapper, activityMapper, userMapper,
                redisTemplate, new ObjectMapper()
        );
        UserHolder.saveUser(userContext(1L));
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
    }

    @Test
    void shouldCreateActivityReviewAndAddAggregateOnce() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1));
        when(reviewMapper.selectByUserAndTargetForUpdate(1L, "ACTIVITY", 1L)).thenReturn(null);
        doAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            review.setId(11L);
            return 1;
        }).when(reviewMapper).insert(any(Review.class));
        when(activityMapper.addReviewScore(1L, score("4.5"))).thenReturn(1);
        when(userMapper.selectById(1L)).thenReturn(user(1L));

        assertThat(reviewService.createReview(createRequest("4.5")).getTargetType())
                .isEqualTo("ACTIVITY");

        verify(activityMapper).addReviewScore(1L, score("4.5"));
        verify(movieMapper, never()).addReviewScore(anyLong(), any(BigDecimal.class));
        verify(redisTemplate, never()).delete("review:like:11");
        verify(redisTemplate, never()).delete("movie:detail:1");
    }

    @Test
    void shouldRejectDuplicateActiveActivityReviewWithoutChangingAggregate() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1));
        when(reviewMapper.selectByUserAndTargetForUpdate(1L, "ACTIVITY", 1L))
                .thenReturn(review(11L, 1L, "4.0", 1));

        assertBusinessError(() -> reviewService.createReview(createRequest("4.5")),
                ErrorCode.DUPLICATE_REVIEW);

        verify(activityMapper, never()).addReviewScore(anyLong(), any(BigDecimal.class));
    }

    @Test
    void shouldRestoreSoftDeletedReviewResetLikesAndAddAggregateOnce() {
        Review deleted = review(11L, 1L, "2.0", 0);
        deleted.setLikeCount(8);
        when(activityMapper.selectById(1L)).thenReturn(activity(1));
        when(reviewMapper.selectByUserAndTargetForUpdate(1L, "ACTIVITY", 1L)).thenReturn(deleted);
        when(reviewMapper.restoreReview(anyLong(), any(), any(), any(), any())).thenReturn(1);
        when(activityMapper.addReviewScore(1L, score("4.5"))).thenReturn(1);
        when(userMapper.selectById(1L)).thenReturn(user(1L));

        assertThat(reviewService.createReview(createRequest("4.5")).getLikeCount()).isZero();

        verify(reviewMapper).restoreReview(anyLong(), any(), any(), any(), any());
        verify(activityMapper).addReviewScore(1L, score("4.5"));
        verify(redisTemplate).delete("review:like:11");
    }

    @Test
    void shouldUpdateOfflineActivityReviewAndKeepScoreCountUnchanged() {
        Review existing = review(11L, 1L, "3.5", 1);
        when(reviewMapper.selectByIdForUpdate(11L)).thenReturn(existing);
        when(activityMapper.selectById(1L)).thenReturn(activity(2));
        when(reviewMapper.updateReview(anyLong(), any(), any(), any(), any())).thenReturn(1);
        when(activityMapper.replaceReviewScore(1L, score("3.5"), score("5.0"))).thenReturn(1);
        when(userMapper.selectById(1L)).thenReturn(user(1L));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        assertThat(reviewService.updateReview(11L, updateRequest("5.0")).getScore())
                .isEqualByComparingTo("5.0");

        verify(activityMapper).replaceReviewScore(1L, score("3.5"), score("5.0"));
        verify(activityMapper, never()).addReviewScore(anyLong(), any(BigDecimal.class));
        verify(activityMapper, never()).removeReviewScore(anyLong(), any(BigDecimal.class));
    }

    @Test
    void shouldSkipActivityAggregateWhenScoreDoesNotChange() {
        Review existing = review(11L, 1L, "4.0", 1);
        when(reviewMapper.selectByIdForUpdate(11L)).thenReturn(existing);
        when(activityMapper.selectById(1L)).thenReturn(activity(2));
        when(reviewMapper.updateReview(anyLong(), any(), any(), any(), any())).thenReturn(1);
        when(userMapper.selectById(1L)).thenReturn(user(1L));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        reviewService.updateReview(11L, updateRequest("4.0"));

        verify(activityMapper, never()).replaceReviewScore(anyLong(), any(), any());
    }

    @Test
    void shouldDeleteOfflineActivityReviewOnlyOnce() {
        Review active = review(11L, 1L, "4.0", 1);
        Review deleted = review(11L, 1L, "4.0", 0);
        when(reviewMapper.selectByIdForUpdate(11L)).thenReturn(active, deleted);
        when(activityMapper.selectById(1L)).thenReturn(activity(2));
        when(reviewMapper.softDelete(anyLong(), any())).thenReturn(1);
        when(activityMapper.removeReviewScore(1L, score("4.0"))).thenReturn(1);

        reviewService.deleteReview(11L);
        reviewService.deleteReview(11L);

        verify(reviewMapper).softDelete(anyLong(), any());
        verify(activityMapper).removeReviewScore(1L, score("4.0"));
        verify(redisTemplate).delete("review:like:11");
    }

    @Test
    void shouldRejectDraftOfflineAndMissingActivityOnCreate() {
        when(activityMapper.selectById(1L)).thenReturn(activity(0), activity(2), null);

        assertBusinessError(() -> reviewService.createReview(createRequest("4.0")),
                ErrorCode.ACTIVITY_NOT_FOUND);
        assertBusinessError(() -> reviewService.createReview(createRequest("4.0")),
                ErrorCode.ACTIVITY_NOT_FOUND);
        assertBusinessError(() -> reviewService.createReview(createRequest("4.0")),
                ErrorCode.ACTIVITY_NOT_FOUND);

        verify(reviewMapper, never()).insert(any(Review.class));
        verify(activityMapper, never()).addReviewScore(anyLong(), any(BigDecimal.class));
    }

    @Test
    void shouldRejectUnsupportedTargetTypeBeforeReadingTarget() {
        CreateReviewDTO request = createRequest("4.0");
        request.setTargetType("activity");

        assertBusinessError(() -> reviewService.createReview(request), ErrorCode.INVALID_TARGET_TYPE);

        verify(activityMapper, never()).selectById(anyLong());
        verify(movieMapper, never()).selectById(anyLong());
    }

    @Test
    void shouldForbidAnotherUserBeforeChangingReviewOrAggregate() {
        Review anotherUsersReview = review(11L, 2L, "4.0", 1);
        when(reviewMapper.selectByIdForUpdate(11L)).thenReturn(anotherUsersReview);

        assertBusinessError(() -> reviewService.updateReview(11L, updateRequest("5.0")),
                ErrorCode.REVIEW_FORBIDDEN);
        assertBusinessError(() -> reviewService.deleteReview(11L),
                ErrorCode.REVIEW_FORBIDDEN);

        verify(reviewMapper, never()).updateReview(anyLong(), any(), any(), any(), any());
        verify(reviewMapper, never()).softDelete(anyLong(), any());
        verify(activityMapper, never()).replaceReviewScore(anyLong(), any(), any());
        verify(activityMapper, never()).removeReviewScore(anyLong(), any(BigDecimal.class));
    }

    @Test
    void shouldFailClosedWhenExistingActivityReviewTargetIsMissing() {
        when(reviewMapper.selectByIdForUpdate(11L)).thenReturn(review(11L, 1L, "4.0", 1));
        when(activityMapper.selectById(1L)).thenReturn(null);

        assertBusinessError(() -> reviewService.updateReview(11L, updateRequest("5.0")),
                ErrorCode.INTERNAL_ERROR);
        assertBusinessError(() -> reviewService.deleteReview(11L),
                ErrorCode.INTERNAL_ERROR);

        verify(reviewMapper, never()).updateReview(anyLong(), any(), any(), any(), any());
        verify(reviewMapper, never()).softDelete(anyLong(), any());
    }

    @Test
    void shouldStopBeforeAggregateWhenReviewInsertFails() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1));
        when(reviewMapper.selectByUserAndTargetForUpdate(1L, "ACTIVITY", 1L)).thenReturn(null);
        when(reviewMapper.insert(any(Review.class))).thenThrow(new IllegalStateException("insert failed"));

        assertThatThrownBy(() -> reviewService.createReview(createRequest("4.0")))
                .isInstanceOf(IllegalStateException.class);

        verify(activityMapper, never()).addReviewScore(anyLong(), any(BigDecimal.class));
    }

    @Test
    void shouldFailWhenActivityAggregateUpdateDoesNotAffectExactlyOneRow() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1));
        when(reviewMapper.selectByUserAndTargetForUpdate(1L, "ACTIVITY", 1L)).thenReturn(null);
        doAnswer(invocation -> {
            Review review = invocation.getArgument(0);
            review.setId(11L);
            return 1;
        }).when(reviewMapper).insert(any(Review.class));
        when(activityMapper.addReviewScore(1L, score("4.0"))).thenReturn(0);

        assertBusinessError(() -> reviewService.createReview(createRequest("4.0")),
                ErrorCode.INTERNAL_ERROR);
    }

    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }

    private CreateReviewDTO createRequest(String value) {
        CreateReviewDTO request = new CreateReviewDTO();
        request.setTargetType("ACTIVITY");
        request.setTargetId(1L);
        request.setScore(score(value));
        request.setContent("Activity review write test");
        request.setImages(Collections.emptyList());
        return request;
    }

    private UpdateReviewDTO updateRequest(String value) {
        UpdateReviewDTO request = new UpdateReviewDTO();
        request.setScore(score(value));
        request.setContent("Updated Activity review");
        request.setImages(Collections.emptyList());
        return request;
    }

    private Activity activity(int status) {
        Activity activity = new Activity();
        activity.setId(1L);
        activity.setStatus(status);
        return activity;
    }

    private Review review(Long id, Long userId, String value, int status) {
        Review review = new Review();
        review.setId(id);
        review.setUserId(userId);
        review.setTargetType("ACTIVITY");
        review.setTargetId(1L);
        review.setScore(score(value));
        review.setContent("Activity review");
        review.setImages("[]");
        review.setLikeCount(0);
        review.setCommentCount(0);
        review.setStatus(status);
        return review;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setNickName("Activity reviewer");
        return user;
    }

    private com.movielife.dto.UserDTO userContext(Long id) {
        com.movielife.dto.UserDTO user = new com.movielife.dto.UserDTO();
        user.setId(id);
        return user;
    }

    private BigDecimal score(String value) {
        return new BigDecimal(value);
    }
}
