package com.movielife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.movielife.constants.MovieStatus;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.MovieDetailCacheDTO;
import com.movielife.entity.Movie;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.CollectionMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.service.impl.MovieServiceImpl;
import com.movielife.utils.UserHolder;
import com.movielife.vo.MovieDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovieServiceImplTest {

    @Mock
    private MovieMapper movieMapper;
    @Mock
    private CollectionMapper collectionMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private MovieServiceImpl movieService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        movieService = new MovieServiceImpl(movieMapper, collectionMapper, redisTemplate, objectMapper);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void cleanup() {
        UserHolder.removeUser();
    }

    @Test
    void cacheHitShouldNotQueryMovieTable() throws Exception {
        MovieDetailCacheDTO cached = cacheDTO();
        when(valueOperations.get("movie:detail:1"))
                .thenReturn(objectMapper.writeValueAsString(cached));

        MovieDetailVO result = movieService.queryMovieDetail(1L);

        assertThat(result.getTitle()).isEqualTo("示例电影");
        assertThat(result.getCollected()).isFalse();
        verify(movieMapper, never()).selectById(any());
    }

    @Test
    void cacheMissShouldQueryDatabaseAndWriteRandomizedCache() {
        when(valueOperations.get("movie:detail:1")).thenReturn(null);
        when(movieMapper.selectById(1L)).thenReturn(movie());

        MovieDetailVO result = movieService.queryMovieDetail(1L);

        assertThat(result.getActors()).containsExactly("演员A", "演员B");
        verify(movieMapper).selectById(1L);
        verify(valueOperations).set(eq("movie:detail:1"), any(String.class), any(Duration.class));
    }

    @Test
    void missingMovieShouldWriteShortEmptyCache() {
        when(valueOperations.get("movie:detail:999")) .thenReturn(null);
        when(movieMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> movieService.queryMovieDetail(999L))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.MOVIE_NOT_FOUND);

        verify(valueOperations).set("movie:detail:999", "", RedisConstants.MOVIE_DETAIL_NULL_TTL);
    }

    @Test
    void emptyCacheShouldBlockRepeatedDatabaseLookup() {
        when(valueOperations.get("movie:detail:999")).thenReturn("");

        assertThatThrownBy(() -> movieService.queryMovieDetail(999L))
                .isInstanceOf(BusinessException.class);

        verify(movieMapper, never()).selectById(any());
    }

    private Movie movie() {
        Movie movie = new Movie();
        movie.setId(1L);
        movie.setTitle("示例电影");
        movie.setReleaseDate(LocalDate.of(2026, 8, 1));
        movie.setActors("演员A, 演员B");
        movie.setStatus(1);
        return movie;
    }

    private MovieDetailCacheDTO cacheDTO() {
        MovieDetailCacheDTO dto = new MovieDetailCacheDTO();
        dto.setId(1L);
        dto.setTitle("示例电影");
        dto.setReleaseDate(LocalDate.of(2026, 8, 1));
        dto.setStatus(MovieStatus.SHOWING);
        return dto;
    }
}
