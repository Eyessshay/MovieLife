package com.movielife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.ai.AiClientResponse;
import com.movielife.ai.ReviewSummaryGenerationResult;
import com.movielife.ai.ReviewSummarySnapshot;
import com.movielife.config.AiReviewSummaryProperties;
import com.movielife.entity.Activity;
import com.movielife.entity.Movie;
import com.movielife.entity.Review;
import com.movielife.enums.AiSummaryTargetType;
import com.movielife.exception.BusinessException;
import com.movielife.mapper.ActivityMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.mapper.ReviewMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewSummaryPipelineTest {

    @Mock
    private ReviewMapper reviewMapper;
    @Mock
    private MovieMapper movieMapper;
    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private AiReviewSummaryClient client;

    @Test
    void moviePolicyShouldAcceptPhysicalMovieRegardlessOfStatus() {
        Movie movie = new Movie();
        movie.setStatus(0);
        when(movieMapper.selectById(1L)).thenReturn(movie);

        new MovieSummaryTargetPolicy(movieMapper).validateForSnapshot(1L);

        when(movieMapper.selectById(2L)).thenReturn(null);
        assertThatThrownBy(() -> new MovieSummaryTargetPolicy(movieMapper).validateForSnapshot(2L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void activityPolicyShouldRequirePublicActiveTarget() {
        Activity active = new Activity();
        active.setStatus(1);
        Activity offline = new Activity();
        offline.setStatus(0);
        when(activityMapper.selectById(1L)).thenReturn(active);
        when(activityMapper.selectById(2L)).thenReturn(offline);

        ActivitySummaryTargetPolicy policy = new ActivitySummaryTargetPolicy(activityMapper);
        policy.validateForSnapshot(1L);
        assertThatThrownBy(() -> policy.validateForSnapshot(2L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void sourceShouldUseNewestActiveRowsAndSameSanitizedInput() {
        AiReviewSummaryProperties properties = properties();
        ReviewSummaryTargetPolicy moviePolicy = policy(AiSummaryTargetType.MOVIE);
        ReviewSummaryTargetPolicy activityPolicy = policy(AiSummaryTargetType.ACTIVITY);
        ReviewSummarySourceService source = new ReviewSummarySourceService(
                new ReviewSummaryTargetPolicyRegistry(Arrays.asList(moviePolicy, activityPolicy)),
                reviewMapper, new ReviewContentSanitizer(), properties);
        when(reviewMapper.countActiveByTarget("MOVIE", 1L)).thenReturn(120);
        when(reviewMapper.selectActiveSummarySource("MOVIE", 1L, 100)).thenReturn(Arrays.asList(
                review(3L, "5.0", " 13812345678 好评 "),
                review(2L, "4.50", "邮箱 a@b.com")));

        ReviewSummarySnapshot result = source.loadSnapshot(AiSummaryTargetType.MOVIE, 1L);

        assertThat(result.getSourceReviewCount()).isEqualTo(120);
        assertThat(result.getReviews()).extracting(item -> item.getReviewId()).containsExactly(3L, 2L);
        assertThat(result.getReviews()).extracting(item -> item.getScore()).containsExactly("5", "4.5");
        assertThat(result.getReviews()).extracting(item -> item.getContent())
                .containsExactly("[PHONE] 好评", "邮箱 [EMAIL]");
        verify(moviePolicy).validateForSnapshot(1L);
    }

    @Test
    void generationShouldReturnInsufficientWithoutCallingModel() {
        ReviewSummarySourceService source = org.mockito.Mockito.mock(ReviewSummarySourceService.class);
        AiReviewSummaryProperties properties = properties();
        ReviewSummarySnapshot snapshot = new ReviewSummarySnapshot(
                AiSummaryTargetType.MOVIE, 1L, 2, Collections.emptyList());
        when(source.loadSnapshot(AiSummaryTargetType.MOVIE, 1L)).thenReturn(snapshot);
        ReviewSummaryGenerationService service = generationService(source, properties);

        ReviewSummaryGenerationResult result = service.generate(AiSummaryTargetType.MOVIE, 1L, 1L);

        assertThat(result.getStatus()).isEqualTo(ReviewSummaryGenerationResult.Status.INSUFFICIENT_REVIEWS);
        assertThat(result.getPublication()).isNull();
        verify(client, never()).generate(any());
    }

    @Test
    void generationShouldBuildPublicationWithoutPersistingIt() {
        ReviewSummarySourceService source = org.mockito.Mockito.mock(ReviewSummarySourceService.class);
        AiReviewSummaryProperties properties = properties();
        ReviewSummarySnapshot snapshot = new ReviewSummarySnapshot(
                AiSummaryTargetType.ACTIVITY, 8L, 3, Arrays.asList(
                item(3L, "5", "很好"), item(2L, "4", "方便"), item(1L, "3", "略贵")));
        when(source.loadSnapshot(AiSummaryTargetType.ACTIVITY, 8L)).thenReturn(snapshot);
        when(client.generate(any())).thenReturn(new AiClientResponse(
                "{\"summary\":\"整体不错\",\"pros\":[\"方便\"],\"cons\":[\"略贵\"],"
                        + "\"suitableFor\":[\"朋友\"],\"warningPoints\":[]}", "test-model"));

        ReviewSummaryGenerationResult result = generationService(source, properties)
                .generate(AiSummaryTargetType.ACTIVITY, 8L, 6L);

        assertThat(result.getStatus()).isEqualTo(ReviewSummaryGenerationResult.Status.GENERATED);
        assertThat(result.getPublication().getSourceVersion()).isEqualTo(6L);
        assertThat(result.getPublication().getSourceFingerprint()).matches("[0-9a-f]{64}");
        assertThat(result.getPublication().getPros()).isEqualTo("[\"方便\"]");
        assertThat(result.getPublication().getModelName()).isEqualTo("test-model");
    }

    private ReviewSummaryGenerationService generationService(ReviewSummarySourceService source,
                                                              AiReviewSummaryProperties properties) {
        ObjectMapper mapper = new ObjectMapper();
        return new ReviewSummaryGenerationService(source, new ReviewSummaryPromptBuilder(mapper),
                new ReviewSummaryFingerprintCalculator(), client,
                new AiSummaryResponseValidator(mapper), properties, mapper);
    }

    private AiReviewSummaryProperties properties() {
        return new AiReviewSummaryProperties();
    }

    private ReviewSummaryTargetPolicy policy(AiSummaryTargetType type) {
        ReviewSummaryTargetPolicy policy = org.mockito.Mockito.mock(ReviewSummaryTargetPolicy.class);
        when(policy.targetType()).thenReturn(type);
        return policy;
    }

    private Review review(Long id, String score, String content) {
        Review review = new Review();
        review.setId(id);
        review.setScore(new BigDecimal(score));
        review.setContent(content);
        return review;
    }

    private com.movielife.ai.ReviewSummaryInputItem item(Long id, String score, String content) {
        return new com.movielife.ai.ReviewSummaryInputItem(id, score, content);
    }
}
