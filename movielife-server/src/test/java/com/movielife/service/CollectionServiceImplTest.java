package com.movielife.service;

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
import com.movielife.service.impl.CollectionServiceImpl;
import com.movielife.utils.JsonListUtils;
import com.movielife.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectionServiceImplTest {

    @Mock private CollectionMapper collectionMapper;
    @Mock private MovieMapper movieMapper;
    @Mock private PostMapper postMapper;
    @Mock private ReviewMapper reviewMapper;
    @Mock private ActivityMapper activityMapper;
    @Mock private JsonListUtils jsonListUtils;

    private CollectionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CollectionServiceImpl(collectionMapper, movieMapper, postMapper,
                reviewMapper, activityMapper, jsonListUtils);
        UserHolder.saveUser(userContext(1L));
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void repeatedActivityCollectShouldBeIdempotent() {
        Activity activity = activeActivity(2L);
        when(activityMapper.selectById(2L)).thenReturn(activity);
        when(collectionMapper.selectCount(any())).thenReturn(1L);

        service.collect("activity", 2L);

        verify(collectionMapper, never()).insert(any());
    }

    @Test
    void unsupportedTargetTypeShouldBeRejected() {
        assertThatThrownBy(() -> service.collect("VENUE", 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_COLLECTION_TARGET_TYPE);
    }

    @Test
    void activeActivityShouldBeCollectible() {
        when(activityMapper.selectById(2L)).thenReturn(activeActivity(2L));
        when(collectionMapper.selectCount(any())).thenReturn(0L);

        service.collect(" ACTIVITY ", 2L);

        verify(collectionMapper).insert(any());
    }

    @Test
    void duplicateKeyRaceShouldConvergeAsIdempotentSuccess() {
        when(activityMapper.selectById(2L)).thenReturn(activeActivity(2L));
        when(collectionMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("concurrent collection"))
                .when(collectionMapper).insert(any());

        service.collect("ACTIVITY", 2L);

        verify(collectionMapper).insert(any());
    }

    @Test
    void missingDraftOrOfflineActivityShouldBeRejected() {
        Activity draft = activeActivity(3L);
        draft.setStatus(0);
        Activity offline = activeActivity(4L);
        offline.setStatus(2);
        when(activityMapper.selectById(2L)).thenReturn(null);
        when(activityMapper.selectById(3L)).thenReturn(draft);
        when(activityMapper.selectById(4L)).thenReturn(offline);

        assertActivityTargetNotFound(2L);
        assertActivityTargetNotFound(3L);
        assertActivityTargetNotFound(4L);
        verify(collectionMapper, never()).insert(any());
    }

    @Test
    void activityUncollectShouldNotRequireVisibleTarget() {
        service.uncollect("ACTIVITY", 2L);

        verify(collectionMapper).delete(any());
        verifyNoInteractions(activityMapper);
    }

    @Test
    void activityCheckShouldOnlyReadCollectionRelation() {
        when(collectionMapper.selectCount(any())).thenReturn(1L);

        assertThat(service.check("ACTIVITY", 2L).getCollected()).isTrue();
        verifyNoInteractions(activityMapper);
    }

    @Test
    void activityCollectionListShouldUseVisiblePageAndBatchLoadTargets() {
        CollectionQueryDTO query = new CollectionQueryDTO();
        query.setTargetType("ACTIVITY");
        query.setPage(2);
        query.setPageSize(2);
        Collection first = collection(11L, 101L, LocalDateTime.of(2026, 9, 14, 12, 0));
        Collection second = collection(10L, 100L, LocalDateTime.of(2026, 9, 14, 12, 0));
        Page<Collection> page = new Page<>(2, 2, 5);
        page.setRecords(Arrays.asList(first, second));
        when(collectionMapper.selectVisibleActivityPage(any(), org.mockito.ArgumentMatchers.eq(1L)))
                .thenReturn(page);
        Activity activity101 = activity(101L, "活动101");
        Activity activity100 = activity(100L, "活动100");
        when(activityMapper.selectBatchIds(any())).thenReturn(Arrays.asList(activity100, activity101));

        com.movielife.vo.PageVO<com.movielife.vo.CollectionVO> result =
                service.queryCollections(query);

        assertThat(result.getTotal()).isEqualTo(5L);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(2);
        assertThat(result.getList()).extracting(com.movielife.vo.CollectionVO::getTargetId)
                .containsExactly(101L, 100L);
        assertThat(result.getList().get(0).getTarget())
                .isInstanceOf(com.movielife.vo.ActivityListItemVO.class);
        assertThat(((com.movielife.vo.ActivityListItemVO) result.getList().get(0).getTarget())
                .getTitle()).isEqualTo("活动101");
        assertThat(((com.movielife.vo.ActivityListItemVO) result.getList().get(0).getTarget())
                .getCollected()).isTrue();
        assertThat(((com.movielife.vo.ActivityListItemVO) result.getList().get(0).getTarget())
                .getAvgScore()).isEqualByComparingTo("4.25");
        assertThat(((com.movielife.vo.ActivityListItemVO) result.getList().get(0).getTarget())
                .getScoreCount()).isEqualTo(2);
        verify(collectionMapper).selectVisibleActivityPage(any(), org.mockito.ArgumentMatchers.eq(1L));
        verify(activityMapper, times(1)).selectBatchIds(any());
        verify(collectionMapper, never()).selectPage(any(), any());
    }

    @Test
    void movieCollectionListShouldKeepGenericPagePath() {
        CollectionQueryDTO query = new CollectionQueryDTO();
        query.setTargetType("MOVIE");
        Page<Collection> page = new Page<>(1, 20, 0);
        page.setRecords(java.util.Collections.emptyList());
        when(collectionMapper.selectPage(any(), any())).thenReturn(page);

        service.queryCollections(query);

        verify(collectionMapper).selectPage(any(), any());
        verify(collectionMapper, never()).selectVisibleActivityPage(any(), any());
        verifyNoInteractions(activityMapper);
    }

    @Test
    void movieCollectionLifecycleShouldRemainCompatible() {
        Movie movie = new Movie();
        movie.setId(2L);
        movie.setTitle("阶段十二电影回归");
        movie.setStatus(1);
        when(movieMapper.selectById(2L)).thenReturn(movie);
        when(collectionMapper.selectCount(any())).thenReturn(0L, 1L);
        Collection relation = collection(20L, 2L, LocalDateTime.now());
        relation.setTargetType("MOVIE");
        Page<Collection> page = new Page<>(1, 20, 1);
        page.setRecords(Collections.singletonList(relation));
        when(collectionMapper.selectPage(any(), any())).thenReturn(page);
        when(movieMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(movie));

        service.collect("MOVIE", 2L);
        assertThat(service.check("MOVIE", 2L).getCollected()).isTrue();
        assertThat(service.queryCollections(query("MOVIE")).getList()).hasSize(1);
        service.uncollect("MOVIE", 2L);

        verify(collectionMapper).insert(any());
        verify(collectionMapper).delete(any());
        verify(movieMapper).selectBatchIds(any());
    }

    @Test
    void postCollectionLifecycleShouldRemainCompatible() {
        Post post = new Post();
        post.setId(3L);
        post.setTitle("阶段十二帖子回归");
        post.setContent("保持阶段四行为");
        post.setLiked(0);
        post.setComments(0);
        when(postMapper.selectById(3L)).thenReturn(post);
        when(collectionMapper.selectCount(any())).thenReturn(0L, 1L);
        Collection relation = collection(21L, 3L, LocalDateTime.now());
        relation.setTargetType("POST");
        Page<Collection> page = new Page<>(1, 20, 1);
        page.setRecords(Collections.singletonList(relation));
        when(collectionMapper.selectPage(any(), any())).thenReturn(page);
        when(postMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(post));

        service.collect("POST", 3L);
        assertThat(service.check("POST", 3L).getCollected()).isTrue();
        assertThat(service.queryCollections(query("POST")).getList()).hasSize(1);
        service.uncollect("POST", 3L);

        verify(collectionMapper).insert(any());
        verify(collectionMapper).delete(any());
        verify(postMapper).selectBatchIds(any());
    }

    @Test
    void reviewCollectionLifecycleShouldRemainCompatible() {
        Review review = new Review();
        review.setId(4L);
        review.setStatus(1);
        review.setScore(new BigDecimal("4.50"));
        review.setContent("阶段十二影评回归");
        review.setLikeCount(0);
        when(reviewMapper.selectById(4L)).thenReturn(review);
        when(collectionMapper.selectCount(any())).thenReturn(0L, 1L);
        Collection relation = collection(22L, 4L, LocalDateTime.now());
        relation.setTargetType("REVIEW");
        Page<Collection> page = new Page<>(1, 20, 1);
        page.setRecords(Collections.singletonList(relation));
        when(collectionMapper.selectPage(any(), any())).thenReturn(page);
        when(reviewMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(review));

        service.collect("REVIEW", 4L);
        assertThat(service.check("REVIEW", 4L).getCollected()).isTrue();
        assertThat(service.queryCollections(query("REVIEW")).getList()).hasSize(1);
        service.uncollect("REVIEW", 4L);

        verify(collectionMapper).insert(any());
        verify(collectionMapper).delete(any());
        verify(reviewMapper).selectBatchIds(any());
    }

    private CollectionQueryDTO query(String targetType) {
        CollectionQueryDTO query = new CollectionQueryDTO();
        query.setTargetType(targetType);
        query.setPage(1);
        query.setPageSize(20);
        return query;
    }

    private Collection collection(Long id, Long targetId, LocalDateTime createTime) {
        Collection collection = new Collection();
        collection.setId(id);
        collection.setUserId(1L);
        collection.setTargetType("ACTIVITY");
        collection.setTargetId(targetId);
        collection.setCreateTime(createTime);
        return collection;
    }

    private Activity activity(Long id, String title) {
        Activity activity = activeActivity(id);
        activity.setTitle(title);
        activity.setCoverUrl("https://example.com/activity.jpg");
        activity.setType("drama");
        activity.setCity("上海");
        activity.setPriceMin(new BigDecimal("99.00"));
        activity.setPriceMax(new BigDecimal("199.00"));
        activity.setAvgScore(new BigDecimal("4.25"));
        activity.setScoreCount(2);
        return activity;
    }

    private void assertActivityTargetNotFound(Long id) {
        assertThatThrownBy(() -> service.collect("ACTIVITY", id))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.COLLECTION_TARGET_NOT_FOUND);
    }

    private Activity activeActivity(Long id) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setStatus(1);
        return activity;
    }

    private com.movielife.dto.UserDTO userContext(Long id) {
        com.movielife.dto.UserDTO user = new com.movielife.dto.UserDTO();
        user.setId(id);
        return user;
    }
}
