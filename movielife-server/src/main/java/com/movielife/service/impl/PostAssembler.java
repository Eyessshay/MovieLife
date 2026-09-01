package com.movielife.service.impl;

import com.movielife.constants.RedisConstants;
import com.movielife.entity.Post;
import com.movielife.entity.User;
import com.movielife.mapper.UserMapper;
import com.movielife.utils.JsonListUtils;
import com.movielife.utils.UserHolder;
import com.movielife.vo.PostUserVO;
import com.movielife.vo.PostVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostAssembler {

    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final JsonListUtils jsonListUtils;

    public List<PostVO> toVOs(List<Post> posts) {
        if (posts.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> userIds = posts.stream().map(Post::getUserId).collect(Collectors.toSet());
        Map<Long, User> users = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return posts.stream().map(post -> toVO(post, users.get(post.getUserId())))
                .collect(Collectors.toList());
    }

    public PostVO toVO(Post post) {
        User user = userMapper.selectById(post.getUserId());
        return toVO(post, user);
    }

    private PostVO toVO(Post post, User user) {
        PostVO vo = new PostVO();
        vo.setId(post.getId());
        vo.setUser(user == null
                ? new PostUserVO(post.getUserId(), null, null)
                : new PostUserVO(user.getId(), user.getNickName(), user.getIcon()));
        vo.setTitle(post.getTitle());
        vo.setContent(post.getContent());
        vo.setImages(jsonListUtils.readStrings(post.getImages()));
        vo.setRelatedType(post.getRelatedType());
        vo.setRelatedId(post.getRelatedId());
        vo.setLikeCount(post.getLiked());
        vo.setCommentCount(post.getComments());
        vo.setLiked(isLiked(post.getId()));
        vo.setCreateTime(post.getCreateTime());
        vo.setUpdateTime(post.getUpdateTime());
        return vo;
    }

    private boolean isLiked(Long postId) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            return false;
        }
        try {
            return stringRedisTemplate.opsForZSet().score(
                    RedisConstants.POST_LIKE_KEY + postId,
                    String.valueOf(userId)
            ) != null;
        } catch (RuntimeException exception) {
            log.warn("Failed to read post liked state, postId={}, userId={}", postId, userId, exception);
            return false;
        }
    }
}
