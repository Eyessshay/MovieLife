package com.movielife.service;

import com.movielife.dto.FeedQueryDTO;
import com.movielife.entity.Post;
import com.movielife.mapper.PostMapper;
import com.movielife.service.impl.FeedServiceImpl;
import com.movielife.service.impl.PostAssembler;
import com.movielife.utils.UserHolder;
import com.movielife.vo.PostVO;
import com.movielife.vo.ScrollVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedServiceImplTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ZSetOperations<String, String> zSetOperations;
    @Mock private PostMapper postMapper;
    @Mock private PostAssembler postAssembler;

    private FeedServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FeedServiceImpl(redisTemplate, postMapper, postAssembler);
        UserHolder.saveUser(userContext(1L));
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void sameTimestampShouldPreserveRedisOrderAndAdvanceOffset() {
        FeedQueryDTO query = new FeedQueryDTO();
        query.setMaxTime(1000L);
        query.setOffset(0);
        query.setPageSize(2);
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>("2", 1000D));
        tuples.add(new DefaultTypedTuple<>("1", 1000D));
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(zSetOperations.reverseRangeByScoreWithScores(any(), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(tuples);
        Post first = post(1L);
        Post second = post(2L);
        when(postMapper.selectBatchIds(any())).thenReturn(Arrays.asList(first, second));
        when(postAssembler.toVOs(any())).thenReturn(Collections.singletonList(new PostVO()));

        ScrollVO<PostVO> result = service.queryFollowingFeed(query);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Post>> captor = ArgumentCaptor.forClass(List.class);
        verify(postAssembler).toVOs(captor.capture());
        assertThat(captor.getValue()).extracting(Post::getId).containsExactly(2L, 1L);
        assertThat(result.getMinTime()).isEqualTo(1000L);
        assertThat(result.getOffset()).isEqualTo(2);
    }

    private Post post(Long id) {
        Post post = new Post();
        post.setId(id);
        post.setUserId(2L);
        return post;
    }

    private com.movielife.dto.UserDTO userContext(Long id) {
        com.movielife.dto.UserDTO user = new com.movielife.dto.UserDTO();
        user.setId(id);
        return user;
    }
}
