package com.movielife.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.VenueDetailCacheDTO;
import com.movielife.dto.VenueQueryDTO;
import com.movielife.entity.Venue;
import com.movielife.enums.VenueType;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.VenueMapper;
import com.movielife.service.VenueService;
import com.movielife.vo.PageVO;
import com.movielife.vo.VenueDetailVO;
import com.movielife.vo.VenueListItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VenueServiceImpl implements VenueService {

    private static final int ENABLED_STATUS = 1;

    private final VenueMapper venueMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public PageVO<VenueListItemVO> queryVenues(VenueQueryDTO queryDTO) {
        LambdaQueryWrapper<Venue> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Venue::getStatus, ENABLED_STATUS)
                .eq(StrUtil.isNotBlank(queryDTO.getCity()), Venue::getCity, queryDTO.getCity())
                .eq(queryDTO.getType() != null, Venue::getType,
                        queryDTO.getType() == null ? null : queryDTO.getType().getDbValue())
                .like(StrUtil.isNotBlank(queryDTO.getKeyword()), Venue::getName, queryDTO.getKeyword())
                .orderByDesc(Venue::getId);

        Page<Venue> venuePage = venueMapper.selectPage(
                new Page<>(queryDTO.getPage(), queryDTO.getPageSize()),
                wrapper
        );
        List<VenueListItemVO> list = venuePage.getRecords().stream()
                .map(this::toListVO)
                .collect(Collectors.toList());

        return new PageVO<>(list, venuePage.getTotal(), queryDTO.getPage(), queryDTO.getPageSize());
    }

    @Override
    public VenueDetailVO queryVenueDetail(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
        return toDetailVO(findPublicVenueDetail(id));
    }

    private VenueDetailCacheDTO findPublicVenueDetail(Long id) {
        String key = RedisConstants.VENUE_DETAIL_KEY + id;
        String json = readCache(key);
        if (json != null) {
            if (json.isEmpty()) {
                throw new BusinessException(ErrorCode.VENUE_NOT_FOUND);
            }
            try {
                return objectMapper.readValue(json, VenueDetailCacheDTO.class);
            } catch (JsonProcessingException exception) {
                log.warn("Invalid venue detail cache, rebuilding key={}", key, exception);
                deleteCache(key);
            }
        }

        Venue venue = venueMapper.selectById(id);
        if (venue == null || !Integer.valueOf(ENABLED_STATUS).equals(venue.getStatus())) {
            writeCache(key, "", RedisConstants.VENUE_DETAIL_NULL_TTL);
            throw new BusinessException(ErrorCode.VENUE_NOT_FOUND);
        }

        VenueDetailCacheDTO cacheDTO = toCacheDTO(venue);
        try {
            long randomMinutes = ThreadLocalRandom.current().nextLong(
                    RedisConstants.VENUE_DETAIL_TTL_RANDOM_MINUTES + 1
            );
            Duration ttl = RedisConstants.VENUE_DETAIL_TTL.plusMinutes(randomMinutes);
            writeCache(key, objectMapper.writeValueAsString(cacheDTO), ttl);
        } catch (JsonProcessingException exception) {
            log.error("Failed to serialize venue detail, venueId={}", id, exception);
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

    private VenueListItemVO toListVO(Venue venue) {
        VenueListItemVO vo = new VenueListItemVO();
        vo.setId(venue.getId());
        vo.setName(venue.getName());
        vo.setType(VenueType.fromDbValue(venue.getType()));
        vo.setCity(venue.getCity());
        vo.setAddress(venue.getAddress());
        vo.setAvgScore(venue.getAvgScore());
        vo.setOpenTime(venue.getOpenTime());
        return vo;
    }

    private VenueDetailCacheDTO toCacheDTO(Venue venue) {
        VenueDetailCacheDTO dto = new VenueDetailCacheDTO();
        dto.setId(venue.getId());
        dto.setName(venue.getName());
        dto.setType(venue.getType());
        dto.setCity(venue.getCity());
        dto.setAddress(venue.getAddress());
        dto.setLongitude(venue.getLongitude());
        dto.setLatitude(venue.getLatitude());
        dto.setPhone(venue.getPhone());
        dto.setOpenTime(venue.getOpenTime());
        dto.setAvgScore(venue.getAvgScore());
        return dto;
    }

    private VenueDetailVO toDetailVO(VenueDetailCacheDTO venue) {
        VenueDetailVO vo = new VenueDetailVO();
        vo.setId(venue.getId());
        vo.setName(venue.getName());
        vo.setType(VenueType.fromDbValue(venue.getType()));
        vo.setCity(venue.getCity());
        vo.setAddress(venue.getAddress());
        vo.setLongitude(venue.getLongitude());
        vo.setLatitude(venue.getLatitude());
        vo.setPhone(venue.getPhone());
        vo.setOpenTime(venue.getOpenTime());
        vo.setAvgScore(venue.getAvgScore());
        return vo;
    }
}
