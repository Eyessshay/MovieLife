package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.MovieStatus;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.MovieDetailCacheDTO;
import com.movielife.dto.MovieQueryDTO;
import com.movielife.entity.Collection;
import com.movielife.entity.Movie;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.CollectionMapper;
import com.movielife.mapper.MovieMapper;
import com.movielife.service.MovieService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.MovieDetailVO;
import com.movielife.vo.MovieListItemVO;
import com.movielife.vo.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class MovieServiceImpl implements MovieService {

    private static final String MOVIE_COLLECTION_TYPE = "MOVIE";

    private final MovieMapper movieMapper;
    private final CollectionMapper collectionMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public PageVO<MovieListItemVO> queryMovies(MovieQueryDTO query) {
        // ① 构造查询条件
        LambdaQueryWrapper<Movie> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(query.getKeyword()), Movie::getTitle, query.getKeyword())
                .eq(query.getStatus() != null, Movie::getStatus,
                        query.getStatus() == null ? null : query.getStatus().getCode())
                .like(StringUtils.hasText(query.getCategory()), Movie::getCategory, query.getCategory())
                .eq(StringUtils.hasText(query.getRegion()), Movie::getRegion, query.getRegion())
                .orderByDesc(Movie::getReleaseDate)
                .orderByDesc(Movie::getId);
        // ② 执行分页查询
        Page<Movie> moviePage = movieMapper.selectPage(
                new Page<>(query.getPage(), query.getPageSize()),
                wrapper
        );
        // 单独查询收藏情况
        Set<Long> collectedIds = findCollectedMovieIds(moviePage.getRecords());
        // 类型转换，添加收藏状态
        List<MovieListItemVO> items = moviePage.getRecords().stream()
                .map(movie -> toListVO(movie, collectedIds.contains(movie.getId())))
                .collect(Collectors.toList());

        return new PageVO<>(items, moviePage.getTotal(), query.getPage(), query.getPageSize());
    }

    @Override
    public MovieDetailVO queryMovieDetail(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
        MovieDetailCacheDTO cachedMovie = findPublicMovieDetail(id);
        return toDetailVO(cachedMovie, isCollected(id));
    }

    private MovieDetailCacheDTO findPublicMovieDetail(Long id) {
        String key = RedisConstants.MOVIE_DETAIL_KEY + id;
        String json = readCache(key);
        if (json != null) {
            if (json.isEmpty()) {
                throw new BusinessException(ErrorCode.MOVIE_NOT_FOUND);
            }
            try {
                return objectMapper.readValue(json, MovieDetailCacheDTO.class);
            } catch (JsonProcessingException exception) {
                log.warn("Invalid movie detail cache, rebuilding key={}", key, exception);
                deleteCache(key);
            }
        }

        Movie movie = movieMapper.selectById(id);
        if (movie == null) {
            writeCache(key, "", RedisConstants.MOVIE_DETAIL_NULL_TTL);
            throw new BusinessException(ErrorCode.MOVIE_NOT_FOUND);
        }

        MovieDetailCacheDTO cacheDTO = toCacheDTO(movie);
        try {
            long randomMinutes = ThreadLocalRandom.current().nextLong(
                    RedisConstants.MOVIE_DETAIL_TTL_RANDOM_MINUTES + 1
            );
            Duration ttl = RedisConstants.MOVIE_DETAIL_TTL.plusMinutes(randomMinutes);
            writeCache(key, objectMapper.writeValueAsString(cacheDTO), ttl);
        } catch (JsonProcessingException exception) {
            log.error("Failed to serialize movie detail, movieId={}", id, exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return cacheDTO;
    }

    private String readCache(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (RuntimeException exception) {
            log.warn("Redis read failed, falling back to MySQL, key={}", key, exception);
            return null;
        }
    }

    private void writeCache(String key, String value, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, value, ttl);
        } catch (RuntimeException exception) {
            log.warn("Redis write failed, response will continue without cache, key={}", key, exception);
        }
    }

    private void deleteCache(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (RuntimeException exception) {
            log.warn("Failed to delete invalid cache, key={}", key, exception);
        }
    }

    private Set<Long> findCollectedMovieIds(List<Movie> movies) {
        Long userId = UserHolder.getUserId();
        if (userId == null || movies.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> movieIds = movies.stream().map(Movie::getId).collect(Collectors.toList());
        List<Collection> collections = collectionMapper.selectList(
                new LambdaQueryWrapper<Collection>()
                        .eq(Collection::getUserId, userId)
                        .eq(Collection::getTargetType, MOVIE_COLLECTION_TYPE)
                        .in(Collection::getTargetId, movieIds)
        );
        return collections.stream().map(Collection::getTargetId).collect(Collectors.toSet());
    }

    private boolean isCollected(Long movieId) {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            return false;
        }
        Long count = collectionMapper.selectCount(
                new LambdaQueryWrapper<Collection>()
                        .eq(Collection::getUserId, userId)
                        .eq(Collection::getTargetType, MOVIE_COLLECTION_TYPE)
                        .eq(Collection::getTargetId, movieId)
        );
        return count != null && count > 0;
    }

    private MovieListItemVO toListVO(Movie movie, boolean collected) {
        MovieListItemVO vo = new MovieListItemVO();
        vo.setId(movie.getId());
        vo.setTitle(movie.getTitle());
        vo.setCoverUrl(movie.getCoverUrl());
        vo.setCategory(movie.getCategory());
        vo.setReleaseDate(movie.getReleaseDate());
        vo.setAvgScore(movie.getAvgScore());
        vo.setScoreCount(movie.getScoreCount());
        vo.setStatus(MovieStatus.fromCode(movie.getStatus()));
        vo.setCollected(collected);
        return vo;
    }

    private MovieDetailCacheDTO toCacheDTO(Movie movie) {
        MovieDetailCacheDTO dto = new MovieDetailCacheDTO();
        dto.setId(movie.getId());
        dto.setTitle(movie.getTitle());
        dto.setOriginalTitle(movie.getOriginalTitle());
        dto.setCoverUrl(movie.getCoverUrl());
        dto.setCategory(movie.getCategory());
        dto.setRegion(movie.getRegion());
        dto.setLanguage(movie.getLanguage());
        dto.setReleaseDate(movie.getReleaseDate());
        dto.setDuration(movie.getDuration());
        dto.setDirector(movie.getDirector());
        dto.setActors(splitActors(movie.getActors()));
        dto.setSummary(movie.getSummary());
        dto.setAvgScore(movie.getAvgScore());
        dto.setScoreCount(movie.getScoreCount());
        dto.setStatus(MovieStatus.fromCode(movie.getStatus()));
        return dto;
    }

    private MovieDetailVO toDetailVO(MovieDetailCacheDTO movie, boolean collected) {
        MovieDetailVO vo = new MovieDetailVO();
        vo.setId(movie.getId());
        vo.setTitle(movie.getTitle());
        vo.setOriginalTitle(movie.getOriginalTitle());
        vo.setCoverUrl(movie.getCoverUrl());
        vo.setCategory(movie.getCategory());
        vo.setRegion(movie.getRegion());
        vo.setLanguage(movie.getLanguage());
        vo.setReleaseDate(movie.getReleaseDate());
        vo.setDuration(movie.getDuration());
        vo.setDirector(movie.getDirector());
        vo.setActors(movie.getActors());
        vo.setSummary(movie.getSummary());
        vo.setAvgScore(movie.getAvgScore());
        vo.setScoreCount(movie.getScoreCount());
        vo.setStatus(movie.getStatus());
        vo.setCollected(collected);
        return vo;
    }

    private List<String> splitActors(String actors) {
        if (!StringUtils.hasText(actors)) {
            return Collections.emptyList();
        }
        return Stream.of(actors.split("[,，]"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }
}
