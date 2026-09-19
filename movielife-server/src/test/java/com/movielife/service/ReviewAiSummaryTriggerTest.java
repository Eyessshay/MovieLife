package com.movielife.service;

import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewAiSummaryTriggerTest {

    @Mock
    private AiSummaryPersistenceService persistenceService;
    @Mock
    private AiReviewSummaryCacheRepository cacheRepository;
    @Mock
    private AiSummaryStreamPublisher streamPublisher;
    @Mock
    private ReviewSummaryTargetPolicyRegistry policyRegistry;
    @Mock
    private ReviewSummaryTargetPolicy targetPolicy;

    private AiReviewSummaryProperties properties;
    private ReviewAiSummaryTrigger trigger;

    @BeforeEach
    void setUp() {
        properties = new AiReviewSummaryProperties();
        trigger = new ReviewAiSummaryTrigger(
                properties, persistenceService, cacheRepository, streamPublisher, policyRegistry);
    }

    @Test
    void disabledFeatureShouldLeaveExistingReviewFlowUntouched() {
        trigger.afterReviewCommitted(AiSummaryTargetType.MOVIE, 1L);

        verifyNoInteractions(persistenceService, cacheRepository, streamPublisher);
    }

    @Test
    void enabledFeatureShouldMarkDirtyBeforeCacheDeleteAndStreamNotification() {
        properties.setEnabled(true);
        when(policyRegistry.require(AiSummaryTargetType.MOVIE)).thenReturn(targetPolicy);

        trigger.afterReviewCommitted(AiSummaryTargetType.MOVIE, 1L);

        InOrder order = inOrder(persistenceService, cacheRepository, streamPublisher);
        order.verify(persistenceService).markDirty(eq(AiSummaryTargetType.MOVIE), eq(1L), any());
        order.verify(cacheRepository).delete(AiSummaryTargetType.MOVIE, 1L);
        order.verify(streamPublisher).publish(AiSummaryTargetType.MOVIE, 1L);
    }

    @Test
    void failedMarkDirtyShouldNotPublishUnrecoverableNotification() {
        properties.setEnabled(true);
        when(policyRegistry.require(AiSummaryTargetType.MOVIE)).thenReturn(targetPolicy);
        when(persistenceService.markDirty(eq(AiSummaryTargetType.MOVIE), eq(1L), any()))
                .thenThrow(new RuntimeException("database unavailable"));

        trigger.afterReviewCommitted(AiSummaryTargetType.MOVIE, 1L);

        verify(cacheRepository, never()).delete(any(), any());
        verify(streamPublisher, never()).publish(any(), any());
    }

    @Test
    void cacheFailureShouldNotBlockDurableTaskNotification() {
        properties.setEnabled(true);
        when(policyRegistry.require(AiSummaryTargetType.MOVIE)).thenReturn(targetPolicy);
        org.mockito.Mockito.doThrow(new RuntimeException("redis cache unavailable"))
                .when(cacheRepository).delete(AiSummaryTargetType.MOVIE, 1L);

        trigger.afterReviewCommitted(AiSummaryTargetType.MOVIE, 1L);

        verify(streamPublisher).publish(AiSummaryTargetType.MOVIE, 1L);
    }

    @Test
    void offlineActivityShouldNotCreateTaskDeleteHistoryOrPublishNotification() {
        properties.setEnabled(true);
        when(policyRegistry.require(AiSummaryTargetType.ACTIVITY)).thenReturn(targetPolicy);
        doThrow(new BusinessException(ErrorCode.ACTIVITY_NOT_FOUND))
                .when(targetPolicy).validateForSnapshot(2L);

        trigger.afterReviewCommitted(AiSummaryTargetType.ACTIVITY, 2L);

        verifyNoInteractions(persistenceService, cacheRepository, streamPublisher);
    }
}
