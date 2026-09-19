package com.movielife.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.dto.ReviewQueryDTO;
import com.movielife.dto.UserDTO;
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
import com.movielife.vo.ReviewVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityReviewQueryServiceTest {

    @Mock
    private ReviewMapper reviewMapper;
    @Mock
    private MovieMapper movieMapper;
    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private MovieReviewAiTrigger movieReviewAiTrigger;

    private ReviewServiceImpl reviewService;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewServiceImpl(
                reviewMapper, movieMapper, activityMapper, userMapper,
                stringRedisTemplate, new ObjectMapper(), movieReviewAiTrigger
        );
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
    }

    @Test
    void anonymousActivityReviewListShouldBatchAuthorsAndSkipRedis() {
        when(activityMapper.selectById(1L)).thenReturn(activity(1));
        when(reviewMapper.selectPage(any(), any())).thenReturn(reviewPage());
        when(userMapper.selectBatchIds(any())).thenReturn(Arrays.asList(user(1L), user(2L)));

        List<ReviewVO> result = reviewService.queryReviews(query("latest")).getList();

        assertThat(result).extracting(ReviewVO::getId).containsExactly(12L, 11L);
        assertThat(result).extracting(ReviewVO::getLiked).containsExactly(false, false);
        ArgumentCaptor<Collection<Long>> authorIds = collectionCaptor();
        verify(userMapper, times(1)).selectBatchIds(authorIds.capture());
        assertThat(authorIds.getValue()).containsExactlyInAnyOrder(1L, 2L);
        verify(userMapper, never()).selectById(anyLong());
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void loggedUserShouldReadExistingReviewLikeZSets() {
        UserDTO currentUser = new UserDTO();
        currentUser.setId(7L);
        UserHolder.saveUser(currentUser);
        when(activityMapper.selectById(1L)).thenReturn(activity(1));
        when(reviewMapper.selectPage(any(), any())).thenReturn(reviewPage());
        when(userMapper.selectBatchIds(any())).thenReturn(Arrays.asList(user(1L), user(2L)));
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.score("review:like:12", "7")).thenReturn(1000D);
        when(zSetOperations.score("review:like:11", "7")).thenReturn(null);

        assertThat(reviewService.queryReviews(query("latest")).getList())
                .extracting(ReviewVO::getLiked)
                .containsExactly(true, false);
    }

    @Test
    void draftOfflineAndMissingActivityShouldNotExposeReviewList() {
        when(activityMapper.selectById(1L)).thenReturn(activity(0), activity(2), null);

        assertBusinessError(() -> reviewService.queryReviews(query("latest")),
                ErrorCode.ACTIVITY_NOT_FOUND);
        assertBusinessError(() -> reviewService.queryReviews(query("latest")),
                ErrorCode.ACTIVITY_NOT_FOUND);
        assertBusinessError(() -> reviewService.queryReviews(query("latest")),
                ErrorCode.ACTIVITY_NOT_FOUND);

        verify(reviewMapper, never()).selectPage(any(), any());
    }

    private ReviewQueryDTO query(String sortBy) {
        ReviewQueryDTO query = new ReviewQueryDTO();
        query.setTargetType("ACTIVITY");
        query.setTargetId(1L);
        query.setSortBy(sortBy);
        return query;
    }

    private Page<Review> reviewPage() {
        Page<Review> page = new Page<>(1, 20, 2);
        page.setRecords(Arrays.asList(
                review(12L, 1L, "4.5", 8, LocalDateTime.of(2026, 9, 16, 12, 0)),
                review(11L, 2L, "3.5", 3, LocalDateTime.of(2026, 9, 16, 11, 0))
        ));
        return page;
    }

    private Review review(Long id, Long userId, String score, int likeCount,
                          LocalDateTime createTime) {
        Review review = new Review();
        review.setId(id);
        review.setUserId(userId);
        review.setTargetType("ACTIVITY");
        review.setTargetId(1L);
        review.setScore(new BigDecimal(score));
        review.setContent("Activity review " + id);
        review.setImages("[]");
        review.setLikeCount(likeCount);
        review.setCommentCount(0);
        review.setStatus(1);
        review.setCreateTime(createTime);
        review.setUpdateTime(createTime);
        return review;
    }

    private Activity activity(int status) {
        Activity activity = new Activity();
        activity.setId(1L);
        activity.setStatus(status);
        return activity;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setNickName("Reviewer " + id);
        return user;
    }

    private void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<Collection<Long>> collectionCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Collection.class);
    }
}
