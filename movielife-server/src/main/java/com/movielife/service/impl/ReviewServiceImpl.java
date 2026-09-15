package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.CreateReviewDTO;
import com.movielife.dto.ReviewQueryDTO;
import com.movielife.dto.UpdateReviewDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Movie;
import com.movielife.entity.Review;
import com.movielife.entity.User;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.ReviewMapper;
import com.movielife.mapper.UserMapper;
import com.movielife.service.ReviewService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.PageVO;
import com.movielife.vo.ReviewLikeVO;
import com.movielife.vo.ReviewUserVO;
import com.movielife.vo.ReviewVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private static final String MOVIE = "MOVIE";
    private static final String ACTIVITY = "ACTIVITY";
    private static final int ACTIVITY_ACTIVE = 1;
    private static final int REVIEW_ACTIVE = 1;
    private static final BigDecimal MIN_SCORE = new BigDecimal("1.0");
    private static final BigDecimal MAX_SCORE = new BigDecimal("5.0");
    private static final BigDecimal SCORE_STEP = new BigDecimal("0.5");
    private static final int CACHE_DELETE_RETRIES = 3;
    private static final long CACHE_DELETE_RETRY_DELAY_MILLIS = 50L;
    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<List<String>>() { };
    private static final DefaultRedisScript<Long> REMOVE_LIKE_SCRIPT = new DefaultRedisScript<>(
            "local score = redis.call('zscore', KEYS[1], ARGV[1]); " +
                    "if not score then return -1; end; " +
                    "redis.call('zrem', KEYS[1], ARGV[1]); return tonumber(score);",
            Long.class
    );

    private final ReviewMapper reviewMapper;
    private final MovieMapper movieMapper;
    private final ActivityMapper activityMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewVO createReview(CreateReviewDTO request) {
        Long userId = requireUserId();
        validateWriteTarget(request.getTargetType(), request.getTargetId());
        validateScore(request.getScore());
        validateCreateTargetExists(request.getTargetType(), request.getTargetId());
        String images = writeImages(request.getImages());

        Review existing = reviewMapper.selectByUserAndTargetForUpdate(
                userId, request.getTargetType(), request.getTargetId()
        );
        Review review;
        boolean restored = false;
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            review = new Review();
            review.setUserId(userId);
            review.setTargetType(request.getTargetType());
            review.setTargetId(request.getTargetId());
            review.setScore(request.getScore());
            review.setContent(request.getContent().trim());
            review.setImages(images);
            review.setLikeCount(0);
            review.setCommentCount(0);
            review.setStatus(REVIEW_ACTIVE);
            review.setCreateTime(now);
            review.setUpdateTime(now);
            try {
                if (reviewMapper.insert(review) != 1) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR);
                }
            } catch (DuplicateKeyException exception) {
                throw new BusinessException(ErrorCode.DUPLICATE_REVIEW);
            }
        } else if (existing.getStatus() != null && existing.getStatus() != 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_REVIEW);
        } else {
            if (reviewMapper.restoreReview(existing.getId(), request.getScore(),
                    request.getContent().trim(), images, now) != 1) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            existing.setScore(request.getScore());
            existing.setContent(request.getContent().trim());
            existing.setImages(images);
            existing.setLikeCount(0);
            existing.setStatus(REVIEW_ACTIVE);
            existing.setCreateTime(now);
            existing.setUpdateTime(now);
            review = existing;
            restored = true;
        }

        addReviewScore(request.getTargetType(), request.getTargetId(), request.getScore());
        invalidateAfterCommit(request.getTargetType(), request.getTargetId(), review.getId(),
                MOVIE.equals(request.getTargetType()) || restored);
        return toReviewVO(review, currentUserMap(userId), false);
    }

    @Override
    public PageVO<ReviewVO> queryReviews(ReviewQueryDTO query) {
        validateTarget(query.getTargetType(), query.getTargetId());
        LambdaQueryWrapper<Review> wrapper = new LambdaQueryWrapper<Review>()
                .eq(Review::getTargetType, query.getTargetType())
                .eq(Review::getTargetId, query.getTargetId())
                .eq(Review::getStatus, REVIEW_ACTIVE);
        if ("likeCount".equals(query.getSortBy())) {
            wrapper.orderByDesc(Review::getLikeCount).orderByDesc(Review::getId);
        } else if ("score".equals(query.getSortBy())) {
            wrapper.orderByDesc(Review::getScore).orderByDesc(Review::getId);
        } else {
            wrapper.orderByDesc(Review::getCreateTime).orderByDesc(Review::getId);
        }

        Page<Review> reviewPage = reviewMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()), wrapper
        );
        Map<Long, User> users = findUsers(reviewPage.getRecords());
        List<ReviewVO> items = reviewPage.getRecords().stream()
                .map(review -> toReviewVO(review, users, isLiked(review.getId())))
                .collect(Collectors.toList());
        return new PageVO<>(items, reviewPage.getTotal(), query.getPage(), query.getPageSize());
    }

    @Override
    public ReviewVO queryReviewDetail(Long id) {
        validateId(id);
        Review review = findActiveReview(id);
        return toReviewVO(review, currentUserMap(review.getUserId()), isLiked(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewVO updateReview(Long id, UpdateReviewDTO request) {
        validateId(id);
        validateScore(request.getScore());
        Long userId = requireUserId();
        Review review = reviewMapper.selectByIdForUpdate(id);
        requireActiveReview(review);
        requireOwner(review, userId);
        validateExistingReviewTarget(review);

        BigDecimal oldScore = review.getScore();
        String images = writeImages(request.getImages());
        LocalDateTime now = LocalDateTime.now();
        int updated = reviewMapper.updateReview(id, request.getScore(), request.getContent().trim(), images, now);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
        replaceReviewScore(review, oldScore, request.getScore());

        review.setScore(request.getScore());
        review.setContent(request.getContent().trim());
        review.setImages(images);
        review.setUpdateTime(now);
        invalidateAfterCommit(review.getTargetType(), review.getTargetId(), review.getId(), false);
        return toReviewVO(review, currentUserMap(userId), isLiked(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteReview(Long id) {
        validateId(id);
        Long userId = requireUserId();
        Review review = reviewMapper.selectByIdForUpdate(id);
        if (review == null) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
        requireOwner(review, userId);
        if (review.getStatus() == null || review.getStatus() == 0) {
            return;
        }
        if (review.getStatus() != REVIEW_ACTIVE) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
        validateExistingReviewTarget(review);

        if (reviewMapper.softDelete(id, LocalDateTime.now()) != 1) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
        removeReviewScore(review);
        invalidateAfterCommit(review.getTargetType(), review.getTargetId(), review.getId(), true);
    }

    @Override
    public ReviewLikeVO likeReview(Long id) {
        validateId(id);
        Long userId = requireUserId();
        findLikeTarget(id);
        String key = RedisConstants.REVIEW_LIKE_KEY + id;
        String member = String.valueOf(userId);
        Boolean added;
        try {
            added = stringRedisTemplate.opsForZSet()
                    .addIfAbsent(key, member, System.currentTimeMillis());
        } catch (RuntimeException exception) {
            log.error("Failed to add review like relation, reviewId={}, userId={}", id, userId, exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (Boolean.TRUE.equals(added)) {
            try {
                if (reviewMapper.incrementLikeCount(id) != 1) {
                    compensateLikeRemoval(key, member, id, userId);
                    throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
                }
            } catch (BusinessException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                compensateLikeRemoval(key, member, id, userId);
                throw exception;
            }
        }
        Review current = findLikeTarget(id);
        return new ReviewLikeVO(true, current.getLikeCount());
    }

    @Override
    public ReviewLikeVO unlikeReview(Long id) {
        validateId(id);
        Long userId = requireUserId();
        findLikeTarget(id);
        String key = RedisConstants.REVIEW_LIKE_KEY + id;
        String member = String.valueOf(userId);
        Long oldScore;
        try {
            oldScore = stringRedisTemplate.execute(
                    REMOVE_LIKE_SCRIPT, Collections.singletonList(key), member
            );
        } catch (RuntimeException exception) {
            log.error("Failed to remove review like relation, reviewId={}, userId={}", id, userId, exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (oldScore != null && oldScore >= 0) {
            try {
                reviewMapper.decrementLikeCount(id);
            } catch (RuntimeException exception) {
                compensateLikeRestore(key, member, oldScore, id, userId);
                throw exception;
            }
        }
        Review current = findLikeTarget(id);
        return new ReviewLikeVO(false, current.getLikeCount());
    }

    private void validateTarget(String targetType, Long targetId) {
        if (!MOVIE.equals(targetType) && !ACTIVITY.equals(targetType)) {
            throw new BusinessException(ErrorCode.INVALID_TARGET_TYPE);
        }
        validateId(targetId);
        if (MOVIE.equals(targetType)) {
            Movie movie = movieMapper.selectById(targetId);
            if (movie == null) {
                throw new BusinessException(ErrorCode.MOVIE_NOT_FOUND);
            }
            return;
        }
        Activity activity = activityMapper.selectById(targetId);
        if (activity == null || activity.getStatus() == null || activity.getStatus() != ACTIVITY_ACTIVE) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
    }

    private void validateWriteTarget(String targetType, Long targetId) {
        if (!MOVIE.equals(targetType) && !ACTIVITY.equals(targetType)) {
            throw new BusinessException(ErrorCode.INVALID_TARGET_TYPE);
        }
        validateId(targetId);
    }

    private void validateCreateTargetExists(String targetType, Long targetId) {
        if (MOVIE.equals(targetType)) {
            if (movieMapper.selectById(targetId) == null) {
                throw new BusinessException(ErrorCode.MOVIE_NOT_FOUND);
            }
            return;
        }
        Activity activity = activityMapper.selectById(targetId);
        if (activity == null || activity.getStatus() == null || activity.getStatus() != ACTIVITY_ACTIVE) {
            throw new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
    }

    private void validateExistingReviewTarget(Review review) {
        if (MOVIE.equals(review.getTargetType())) {
            return;
        }
        if (ACTIVITY.equals(review.getTargetType())) {
            if (activityMapper.selectById(review.getTargetId()) == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
            return;
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR);
    }

    private void validateScore(BigDecimal score) {
        if (score == null || score.compareTo(MIN_SCORE) < 0 || score.compareTo(MAX_SCORE) > 0) {
            throw new BusinessException(ErrorCode.INVALID_SCORE);
        }
        if (score.remainder(SCORE_STEP).compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException(ErrorCode.INVALID_SCORE_STEP);
        }
    }

    private void validateId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
    }

    private Long requireUserId() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private Review findActiveReview(Long id) {
        Review review = reviewMapper.selectById(id);
        requireActiveReview(review);
        return review;
    }

    private Review findLikeTarget(Long id) {
        Review review = reviewMapper.selectById(id);
        if (review == null || review.getStatus() == null || review.getStatus() != REVIEW_ACTIVE) {
            throw new BusinessException(ErrorCode.LIKE_TARGET_INVALID);
        }
        return review;
    }

    private void requireActiveReview(Review review) {
        if (review == null || review.getStatus() == null || review.getStatus() != REVIEW_ACTIVE) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_FOUND);
        }
    }

    private void requireOwner(Review review, Long userId) {
        if (!userId.equals(review.getUserId())) {
            throw new BusinessException(ErrorCode.REVIEW_FORBIDDEN);
        }
    }

    private void ensureAggregateUpdated(int updated) {
        if (updated != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void addReviewScore(String targetType, Long targetId, BigDecimal score) {
        int updated = MOVIE.equals(targetType)
                ? movieMapper.addReviewScore(targetId, score)
                : activityMapper.addReviewScore(targetId, score);
        ensureAggregateUpdated(updated);
    }

    private void replaceReviewScore(Review review, BigDecimal oldScore, BigDecimal newScore) {
        if (ACTIVITY.equals(review.getTargetType())) {
            if (oldScore.compareTo(newScore) != 0) {
                ensureAggregateUpdated(activityMapper.replaceReviewScore(
                        review.getTargetId(), oldScore, newScore
                ));
            }
            return;
        }
        ensureAggregateUpdated(movieMapper.replaceReviewScore(
                review.getTargetId(), oldScore, newScore
        ));
    }

    private void removeReviewScore(Review review) {
        int updated = MOVIE.equals(review.getTargetType())
                ? movieMapper.removeReviewScore(review.getTargetId(), review.getScore())
                : activityMapper.removeReviewScore(review.getTargetId(), review.getScore());
        ensureAggregateUpdated(updated);
    }

    private String writeImages(List<String> images) {
        try {
            return objectMapper.writeValueAsString(images == null ? Collections.emptyList() : images);
        } catch (JsonProcessingException exception) {
            log.error("Failed to serialize review images", exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private List<String> readImages(String images) {
        if (images == null || images.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(images, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            log.error("Invalid review images JSON", exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private Map<Long, User> findUsers(List<Review> reviews) {
        Set<Long> userIds = reviews.stream().map(Review::getUserId).collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));
    }

    private Map<Long, User> currentUserMap(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        Map<Long, User> users = new HashMap<>();
        users.put(userId, user);
        return users;
    }

    private ReviewVO toReviewVO(Review review, Map<Long, User> users, boolean liked) {
        User user = users.get(review.getUserId());
        ReviewVO vo = new ReviewVO();
        vo.setId(review.getId());
        vo.setUser(user == null
                ? new ReviewUserVO(review.getUserId(), null, null)
                : new ReviewUserVO(user.getId(), user.getNickName(), user.getIcon()));
        vo.setTargetType(review.getTargetType());
        vo.setTargetId(review.getTargetId());
        vo.setScore(review.getScore());
        vo.setContent(review.getContent());
        vo.setImages(readImages(review.getImages()));
        vo.setLikeCount(review.getLikeCount());
        vo.setCommentCount(review.getCommentCount());
        vo.setLiked(liked);
        vo.setCreateTime(review.getCreateTime());
        vo.setUpdateTime(review.getUpdateTime());
        return vo;
    }

    private boolean isLiked(Long reviewId) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            return false;
        }
        try {
            return stringRedisTemplate.opsForZSet().score(
                    RedisConstants.REVIEW_LIKE_KEY + reviewId,
                    String.valueOf(userId)
            ) != null;
        } catch (RuntimeException exception) {
            log.warn("Failed to read review liked state, reviewId={}, userId={}",
                    reviewId, userId, exception);
            return false;
        }
    }

    private void compensateLikeRemoval(String key, String member, Long reviewId, Long userId) {
        try {
            stringRedisTemplate.opsForZSet().remove(key, member);
        } catch (RuntimeException compensationException) {
            log.error("Like compensation failed after database error, reviewId={}, userId={}",
                    reviewId, userId, compensationException);
        }
    }

    private void compensateLikeRestore(String key, String member, long score, Long reviewId, Long userId) {
        try {
            stringRedisTemplate.opsForZSet().add(key, member, score);
        } catch (RuntimeException compensationException) {
            log.error("Unlike compensation failed after database error, reviewId={}, userId={}",
                    reviewId, userId, compensationException);
        }
    }

    private void invalidateAfterCommit(String targetType, Long targetId, Long reviewId, boolean removeLikes) {
        Runnable invalidation = () -> {
            if (MOVIE.equals(targetType)) {
                deleteWithRetry(RedisConstants.MOVIE_DETAIL_KEY + targetId,
                        "movie detail", targetType, targetId, reviewId);
            }
            if (removeLikes) {
                deleteWithRetry(RedisConstants.REVIEW_LIKE_KEY + reviewId,
                        "review likes", targetType, targetId, reviewId);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidation.run();
                }
            });
        } else {
            invalidation.run();
        }
    }

    private boolean deleteWithRetry(String key, String cacheType, String targetType,
                                    Long targetId, Long reviewId) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= CACHE_DELETE_RETRIES; attempt++) {
            try {
                stringRedisTemplate.delete(key);
                return true;
            } catch (RuntimeException exception) {
                lastException = exception;
                if (attempt < CACHE_DELETE_RETRIES) {
                    try {
                        Thread.sleep(CACHE_DELETE_RETRY_DELAY_MILLIS);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        lastException = new IllegalStateException("Cache invalidation retry interrupted",
                                interruptedException);
                        break;
                    }
                }
            }
        }
        log.error("Review cache invalidation failed after retries, cacheType={}, targetType={}, " +
                        "targetId={}, reviewId={}",
                cacheType, targetType, targetId, reviewId, lastException);
        return false;
    }
}
