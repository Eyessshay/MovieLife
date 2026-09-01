package com.movielife.service.impl;

import com.movielife.constants.RedisConstants;
import com.movielife.dto.FeedQueryDTO;
import com.movielife.entity.Post;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.PostMapper;
import com.movielife.service.FeedService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.PostVO;
import com.movielife.vo.ScrollVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService {

    private final StringRedisTemplate stringRedisTemplate;
    private final PostMapper postMapper;
    private final PostAssembler postAssembler;

    @Override
    public ScrollVO<PostVO> queryFollowingFeed(FeedQueryDTO query) {
        Long userId = requireUserId();
        long maxTime = query.getMaxTime() == null ? System.currentTimeMillis() : query.getMaxTime();
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> tuples;
        try {
            tuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(
                    key, 0D, (double) maxTime, query.getOffset(), query.getPageSize()
            );
        } catch (RuntimeException exception) {
            log.error("Failed to query following feed, userId={}", userId, exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (tuples == null || tuples.isEmpty()) {
            return new ScrollVO<>(Collections.emptyList(), maxTime, 0);
        }

        List<FeedItem> feedItems = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }
            try {
                feedItems.add(new FeedItem(Long.valueOf(tuple.getValue()), tuple.getScore().longValue()));
            } catch (NumberFormatException exception) {
                stringRedisTemplate.opsForZSet().remove(key, tuple.getValue());
            }
        }
        if (feedItems.isEmpty()) {
            return new ScrollVO<>(Collections.emptyList(), maxTime, 0);
        }

        List<Long> postIds = feedItems.stream().map(item -> item.postId).collect(Collectors.toList());
        Map<Long, Post> postMap = postMapper.selectBatchIds(postIds).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity()));
        List<Post> orderedPosts = new ArrayList<>();
        for (Long postId : postIds) {
            Post post = postMap.get(postId);
            if (post == null) {
                stringRedisTemplate.opsForZSet().remove(key, String.valueOf(postId));
            } else {
                orderedPosts.add(post);
            }
        }

        long minTime = feedItems.get(feedItems.size() - 1).timestamp;
        int sameTimeCount = 0;
        for (FeedItem item : feedItems) {
            if (item.timestamp == minTime) {
                sameTimeCount++;
            }
        }
        int nextOffset = minTime == maxTime ? query.getOffset() + sameTimeCount : sameTimeCount;
        return new ScrollVO<>(postAssembler.toVOs(orderedPosts), minTime, nextOffset);
    }

    private Long requireUserId() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private static final class FeedItem {
        private final Long postId;
        private final long timestamp;

        private FeedItem(Long postId, long timestamp) {
            this.postId = postId;
            this.timestamp = timestamp;
        }
    }
}
