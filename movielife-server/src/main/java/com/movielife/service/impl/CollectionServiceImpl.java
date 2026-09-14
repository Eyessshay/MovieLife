package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.movielife.constants.MovieStatus;
import com.movielife.dto.CollectionQueryDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Collection;
import com.movielife.entity.Movie;
import com.movielife.entity.Post;
import com.movielife.entity.Review;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.CollectionMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.PostMapper;
import com.movielife.mapper.ReviewMapper;
import com.movielife.service.CollectionService;
import com.movielife.utils.JsonListUtils;
import com.movielife.utils.UserHolder;
import com.movielife.vo.CollectionCheckVO;
import com.movielife.vo.CollectionVO;
import com.movielife.vo.ActivityListItemVO;
import com.movielife.vo.MovieListItemVO;
import com.movielife.vo.PageVO;
import com.movielife.vo.PostSummaryVO;
import com.movielife.vo.ReviewSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CollectionServiceImpl implements CollectionService {

    private static final String MOVIE = "MOVIE";
    private static final String POST = "POST";
    private static final String REVIEW = "REVIEW";
    private static final String ACTIVITY = "ACTIVITY";

    private final CollectionMapper collectionMapper;
    private final MovieMapper movieMapper;
    private final PostMapper postMapper;
    private final ReviewMapper reviewMapper;
    private final ActivityMapper activityMapper;
    private final JsonListUtils jsonListUtils;

    @Override
    public void collect(String targetType, Long targetId) {
        Long userId = requireUserId();
        String normalizedType = normalizeType(targetType);
        validateId(targetId);
        requireTarget(normalizedType, targetId);

        Long count = collectionMapper.selectCount(query(userId, normalizedType, targetId));
        if (count != null && count > 0) {
            return;
        }
        Collection collection = new Collection();
        collection.setUserId(userId);
        collection.setTargetType(normalizedType);
        collection.setTargetId(targetId);
        collection.setCreateTime(LocalDateTime.now());
        try {
            collectionMapper.insert(collection);
        } catch (DuplicateKeyException ignored) {
            // A concurrent idempotent PUT already created the same relation.
        }
    }

    @Override
    public void uncollect(String targetType, Long targetId) {
        collectionMapper.delete(query(requireUserId(), normalizeType(targetType), validateId(targetId)));
    }

    @Override
    public PageVO<CollectionVO> queryCollections(CollectionQueryDTO query) {
        Long userId = requireUserId();
        String targetType = normalizeType(query.getTargetType());
        Page<Collection> page;
        if (ACTIVITY.equals(targetType)) {
            page = collectionMapper.selectVisibleActivityPage(
                    new Page<>(query.getPage(), query.getPageSize()), userId);
        } else {
            page = collectionMapper.selectPage(
                    new Page<>(query.getPage(), query.getPageSize()),
                    new LambdaQueryWrapper<Collection>()
                            .eq(Collection::getUserId, userId)
                            .eq(Collection::getTargetType, targetType)
                            .orderByDesc(Collection::getCreateTime)
                            .orderByDesc(Collection::getId)
            );
        }
        Map<Long, Object> targets = findTargets(targetType, page.getRecords());
        List<CollectionVO> items = page.getRecords().stream()
                .filter(item -> targets.containsKey(item.getTargetId()))
                .map(item -> new CollectionVO(item.getTargetType(), item.getTargetId(),
                        targets.get(item.getTargetId()), item.getCreateTime()))
                .collect(Collectors.toList());
        return new PageVO<>(items, page.getTotal(), query.getPage(), query.getPageSize());
    }

    @Override
    public CollectionCheckVO check(String targetType, Long targetId) {
        Long count = collectionMapper.selectCount(query(
                requireUserId(), normalizeType(targetType), validateId(targetId)
        ));
        return new CollectionCheckVO(count != null && count > 0);
    }

    private Map<Long, Object> findTargets(String targetType, List<Collection> collections) {
        if (collections.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> ids = collections.stream().map(Collection::getTargetId).collect(Collectors.toList());
        Map<Long, Object> result = new HashMap<>();
        if (MOVIE.equals(targetType)) {
            for (Movie movie : movieMapper.selectBatchIds(ids)) {
                MovieListItemVO target = new MovieListItemVO();
                target.setId(movie.getId());
                target.setTitle(movie.getTitle());
                target.setCoverUrl(movie.getCoverUrl());
                target.setCategory(movie.getCategory());
                target.setReleaseDate(movie.getReleaseDate());
                target.setAvgScore(movie.getAvgScore());
                target.setScoreCount(movie.getScoreCount());
                target.setStatus(MovieStatus.fromCode(movie.getStatus()));
                target.setCollected(true);
                result.put(movie.getId(), target);
            }
        } else if (POST.equals(targetType)) {
            for (Post post : postMapper.selectBatchIds(ids)) {
                result.put(post.getId(), new PostSummaryVO(post.getId(), post.getTitle(), post.getContent(),
                        jsonListUtils.readStrings(post.getImages()), post.getLiked(), post.getComments()));
            }
        } else if (REVIEW.equals(targetType)) {
            Map<Long, Review> reviews = reviewMapper.selectBatchIds(ids).stream()
                    .filter(review -> review.getStatus() != null && review.getStatus() == 1)
                    .collect(Collectors.toMap(Review::getId, Function.identity()));
            for (Review review : reviews.values()) {
                result.put(review.getId(), new ReviewSummaryVO(review.getId(), review.getScore(),
                        review.getContent(), jsonListUtils.readStrings(review.getImages()),
                        review.getLikeCount()));
            }
        } else {
            for (Activity activity : activityMapper.selectBatchIds(ids)) {
                if (activity.getStatus() == null || activity.getStatus() != 1) {
                    continue;
                }
                ActivityListItemVO target = new ActivityListItemVO();
                target.setId(activity.getId());
                target.setTitle(activity.getTitle());
                target.setCoverUrl(activity.getCoverUrl());
                target.setType(activity.getType());
                target.setCity(activity.getCity());
                target.setPriceMin(activity.getPriceMin());
                target.setPriceMax(activity.getPriceMax());
                result.put(activity.getId(), target);
            }
        }
        return result;
    }

    private void requireTarget(String targetType, Long targetId) {
        boolean exists;
        if (MOVIE.equals(targetType)) {
            exists = movieMapper.selectById(targetId) != null;
        } else if (POST.equals(targetType)) {
            exists = postMapper.selectById(targetId) != null;
        } else if (REVIEW.equals(targetType)) {
            Review review = reviewMapper.selectById(targetId);
            exists = review != null && review.getStatus() != null && review.getStatus() == 1;
        } else {
            Activity activity = activityMapper.selectById(targetId);
            exists = activity != null
                    && activity.getStatus() != null
                    && activity.getStatus() == 1;
        }
        if (!exists) {
            throw new BusinessException(ErrorCode.COLLECTION_TARGET_NOT_FOUND);
        }
    }

    private LambdaQueryWrapper<Collection> query(Long userId, String targetType, Long targetId) {
        return new LambdaQueryWrapper<Collection>()
                .eq(Collection::getUserId, userId)
                .eq(Collection::getTargetType, targetType)
                .eq(Collection::getTargetId, targetId);
    }

    private String normalizeType(String targetType) {
        if (targetType == null) {
            throw new BusinessException(ErrorCode.INVALID_COLLECTION_TARGET_TYPE);
        }
        String normalized = targetType.trim().toUpperCase(Locale.ROOT);
        if (!MOVIE.equals(normalized) && !POST.equals(normalized)
                && !REVIEW.equals(normalized) && !ACTIVITY.equals(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_COLLECTION_TARGET_TYPE);
        }
        return normalized;
    }

    private Long validateId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
        return id;
    }

    private Long requireUserId() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }
}
