package com.movielife.service;

import com.movielife.dto.CollectionQueryDTO;
import com.movielife.entity.Activity;
import com.movielife.entity.Collection;
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
