package com.movielife.service;

import com.movielife.dto.CollectionQueryDTO;
import com.movielife.entity.Activity;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void activityCollectionListShouldRemainOutsideStep121() {
        CollectionQueryDTO query = new CollectionQueryDTO();
        query.setTargetType("ACTIVITY");

        assertThatThrownBy(() -> service.queryCollections(query))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_COLLECTION_TARGET_TYPE);
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
