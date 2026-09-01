package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.CreatePostDTO;
import com.movielife.dto.PostQueryDTO;
import com.movielife.dto.UpdatePostDTO;
import com.movielife.entity.Collection;
import com.movielife.entity.Follow;
import com.movielife.entity.Post;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.CollectionMapper;
import com.movielife.mapper.FollowMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.PostMapper;
import com.movielife.service.PostService;
import com.movielife.utils.JsonListUtils;
import com.movielife.utils.UserHolder;
import com.movielife.vo.PageVO;
import com.movielife.vo.PostLikeVO;
import com.movielife.vo.PostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private static final String MOVIE = "MOVIE";
    private static final String POST = "POST";
    private static final DefaultRedisScript<Long> REMOVE_LIKE_SCRIPT = new DefaultRedisScript<>(
            "local score = redis.call('zscore', KEYS[1], ARGV[1]); " +
                    "if not score then return -1; end; " +
                    "redis.call('zrem', KEYS[1], ARGV[1]); return tonumber(score);",
            Long.class
    );

    private final PostMapper postMapper;
    private final MovieMapper movieMapper;
    private final FollowMapper followMapper;
    private final CollectionMapper collectionMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final JsonListUtils jsonListUtils;
    private final PostAssembler postAssembler;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostVO create(CreatePostDTO request) {
        Long userId = requireUserId();
        RelatedTarget related = validateRelated(request.getRelatedType(), request.getRelatedId());
        LocalDateTime now = LocalDateTime.now();

        Post post = new Post();
        post.setUserId(userId);
        post.setTitle(request.getTitle().trim());
        post.setContent(request.getContent().trim());
        post.setImages(jsonListUtils.writeStrings(request.getImages()));
        post.setRelatedType(related.type);
        post.setRelatedId(related.id);
        post.setLiked(0);
        post.setComments(0);
        post.setCreateTime(now);
        post.setUpdateTime(now);
        postMapper.insert(post);

        List<Long> followerIds = followMapper.selectList(new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getFollowUserId, userId))
                .stream().map(Follow::getUserId).collect(Collectors.toList());
        publishToFeedAfterCommit(post.getId(), now, followerIds);
        return postAssembler.toVO(post);
    }

    @Override
    public PageVO<PostVO> query(PostQueryDTO query) {
        RelatedTarget related = validateQueryRelated(query.getRelatedType(), query.getRelatedId());
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .eq(query.getUserId() != null, Post::getUserId, query.getUserId())
                .eq(related.type != null, Post::getRelatedType, related.type)
                .eq(related.id != null, Post::getRelatedId, related.id)
                .orderByDesc(Post::getCreateTime)
                .orderByDesc(Post::getId);
        Page<Post> page = postMapper.selectPage(new Page<>(query.getPage(), query.getPageSize()), wrapper);
        return new PageVO<>(postAssembler.toVOs(page.getRecords()), page.getTotal(),
                query.getPage(), query.getPageSize());
    }

    @Override
    public PostVO detail(Long id) {
        return postAssembler.toVO(requirePost(id));
    }

    @Override
    public PostVO update(Long id, UpdatePostDTO request) {
        Long userId = requireUserId();
        Post post = requirePost(id);
        requireOwner(post, userId);
        RelatedTarget related = validateRelated(request.getRelatedType(), request.getRelatedId());
        post.setTitle(request.getTitle().trim());
        post.setContent(request.getContent().trim());
        post.setImages(jsonListUtils.writeStrings(request.getImages()));
        post.setRelatedType(related.type);
        post.setRelatedId(related.id);
        post.setUpdateTime(LocalDateTime.now());
        if (postMapper.updateById(post) != 1) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        return postAssembler.toVO(post);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long userId = requireUserId();
        Post post = requirePost(id);
        requireOwner(post, userId);
        if (postMapper.deleteById(id) != 1) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        collectionMapper.delete(new LambdaQueryWrapper<Collection>()
                .eq(Collection::getTargetType, POST)
                .eq(Collection::getTargetId, id));
        afterCommit(() -> deleteRedisKey(RedisConstants.POST_LIKE_KEY + id));
    }

    @Override
    public PostLikeVO like(Long id) {
        Long userId = requireUserId();
        requirePost(id);
        String key = RedisConstants.POST_LIKE_KEY + id;
        String member = String.valueOf(userId);
        Boolean added;
        try {
            added = stringRedisTemplate.opsForZSet().addIfAbsent(key, member, System.currentTimeMillis());
        } catch (RuntimeException exception) {
            log.error("Failed to add post like relation, postId={}, userId={}", id, userId, exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (Boolean.TRUE.equals(added) && postMapper.incrementLikeCount(id) != 1) {
            compensateLikeRemoval(key, member, id, userId);
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        Post current = requirePost(id);
        return new PostLikeVO(true, current.getLiked());
    }

    @Override
    public PostLikeVO unlike(Long id) {
        Long userId = requireUserId();
        requirePost(id);
        String key = RedisConstants.POST_LIKE_KEY + id;
        String member = String.valueOf(userId);
        Long oldScore;
        try {
            oldScore = stringRedisTemplate.execute(REMOVE_LIKE_SCRIPT,
                    Collections.singletonList(key), member);
        } catch (RuntimeException exception) {
            log.error("Failed to remove post like relation, postId={}, userId={}", id, userId, exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (oldScore != null && oldScore >= 0) {
            try {
                postMapper.decrementLikeCount(id);
            } catch (RuntimeException exception) {
                compensateLikeRestore(key, member, oldScore, id, userId);
                throw exception;
            }
        }
        Post current = requirePost(id);
        return new PostLikeVO(false, current.getLiked());
    }

    private RelatedTarget validateRelated(String type, Long id) {
        if (!StringUtils.hasText(type) && id == null) {
            return new RelatedTarget(null, null);
        }
        if (!StringUtils.hasText(type) || id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_RELATED_TARGET);
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!MOVIE.equals(normalized) || movieMapper.selectById(id) == null) {
            throw new BusinessException(ErrorCode.INVALID_RELATED_TARGET);
        }
        return new RelatedTarget(normalized, id);
    }

    private RelatedTarget validateQueryRelated(String type, Long id) {
        if (!StringUtils.hasText(type) && id == null) {
            return new RelatedTarget(null, null);
        }
        if (!StringUtils.hasText(type) || id == null || id <= 0
                || !MOVIE.equals(type.trim().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.INVALID_RELATED_TARGET);
        }
        return new RelatedTarget(MOVIE, id);
    }

    private Post requirePost(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
        Post post = postMapper.selectById(id);
        if (post == null) {
            throw new BusinessException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    private void requireOwner(Post post, Long userId) {
        if (!userId.equals(post.getUserId())) {
            throw new BusinessException(ErrorCode.POST_FORBIDDEN);
        }
    }

    private void publishToFeedAfterCommit(Long postId, LocalDateTime createTime, List<Long> followerIds) {
        long timestamp = createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        afterCommit(() -> {
            for (Long followerId : followerIds) {
                try {
                    stringRedisTemplate.opsForZSet().add(
                            RedisConstants.FEED_KEY + followerId,
                            String.valueOf(postId), timestamp
                    );
                } catch (RuntimeException exception) {
                    log.error("Failed to push post to follower feed, postId={}, followerId={}",
                            postId, followerId, exception);
                }
            }
        });
    }

    private void compensateLikeRemoval(String key, String member, Long postId, Long userId) {
        try {
            stringRedisTemplate.opsForZSet().remove(key, member);
        } catch (RuntimeException exception) {
            log.error("Post-like compensation failed, postId={}, userId={}", postId, userId, exception);
        }
    }

    private void compensateLikeRestore(String key, String member, long score, Long postId, Long userId) {
        try {
            stringRedisTemplate.opsForZSet().add(key, member, score);
        } catch (RuntimeException exception) {
            log.error("Post-unlike compensation failed, postId={}, userId={}", postId, userId, exception);
        }
    }

    private void deleteRedisKey(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (RuntimeException exception) {
            log.error("Failed to delete post Redis state, key={}", key, exception);
        }
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private Long requireUserId() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private static final class RelatedTarget {
        private final String type;
        private final Long id;

        private RelatedTarget(String type, Long id) {
            this.type = type;
            this.id = id;
        }
    }
}
