package com.movielife.service;

import com.movielife.dto.CreatePostDTO;
import com.movielife.entity.Follow;
import com.movielife.entity.Post;
import com.movielife.mapper.CollectionMapper;
import com.movielife.mapper.FollowMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.PostMapper;
import com.movielife.service.impl.PostAssembler;
import com.movielife.service.impl.PostServiceImpl;
import com.movielife.utils.JsonListUtils;
import com.movielife.utils.UserHolder;
import com.movielife.vo.PostLikeVO;
import com.movielife.vo.PostVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {

    @Mock private PostMapper postMapper;
    @Mock private MovieMapper movieMapper;
    @Mock private FollowMapper followMapper;
    @Mock private CollectionMapper collectionMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ZSetOperations<String, String> zSetOperations;
    @Mock private JsonListUtils jsonListUtils;
    @Mock private PostAssembler postAssembler;

    private PostServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PostServiceImpl(postMapper, movieMapper, followMapper, collectionMapper,
                redisTemplate, jsonListUtils, postAssembler);
        UserHolder.saveUser(userContext(1L));
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void createShouldPushToFollowersAfterDatabaseWork() {
        CreatePostDTO request = new CreatePostDTO();
        request.setTitle("新帖子");
        request.setContent("帖子内容");
        request.setImages(Collections.emptyList());
        when(jsonListUtils.writeStrings(any())).thenReturn("[]");
        doAnswer(invocation -> {
            Post post = invocation.getArgument(0);
            post.setId(10L);
            return 1;
        }).when(postMapper).insert(any(Post.class));
        Follow follower = new Follow();
        follower.setUserId(2L);
        when(followMapper.selectList(any())).thenReturn(Collections.singletonList(follower));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(postAssembler.toVO(any(Post.class))).thenReturn(new PostVO());

        service.create(request);

        verify(zSetOperations).add(eq("feed:2"), eq("10"), anyDouble());
    }

    @Test
    void repeatedLikeShouldNotIncrementDatabaseAgain() {
        Post post = post(10L, 1);
        when(postMapper.selectById(10L)).thenReturn(post);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.addIfAbsent(eq("post:like:10"), eq("1"), anyDouble()))
                .thenReturn(false);

        PostLikeVO result = service.like(10L);

        assertThat(result.getLiked()).isTrue();
        assertThat(result.getLikeCount()).isEqualTo(1);
        verify(postMapper, never()).incrementLikeCount(10L);
    }

    private Post post(Long id, int liked) {
        Post post = new Post();
        post.setId(id);
        post.setUserId(1L);
        post.setLiked(liked);
        post.setComments(0);
        return post;
    }

    private com.movielife.dto.UserDTO userContext(Long id) {
        com.movielife.dto.UserDTO user = new com.movielife.dto.UserDTO();
        user.setId(id);
        return user;
    }
}
