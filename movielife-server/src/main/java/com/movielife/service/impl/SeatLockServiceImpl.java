package com.movielife.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.SeatLockContext;
import com.movielife.dto.SeatLockMetadata;
import com.movielife.dto.SeatLockRequest;
import com.movielife.dto.SeatReleaseRequest;
import com.movielife.entity.Seat;
import com.movielife.entity.ShowtimeSeat;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeSeatMapper;
import com.movielife.service.SeatLockService;
import com.movielife.service.ShowtimeService;
import com.movielife.utils.UserHolder;
import com.movielife.vo.SeatLockVO;
import com.movielife.vo.ShowtimeDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeatLockServiceImpl implements SeatLockService {

    private static final int MAX_SEATS_PER_LOCK = 6;
    private static final int ENABLED_STATUS = 1;
    private static final int AVAILABLE_STATUS = 0;
    private static final String ACTIVITY_BIZ_TYPE = "ACTIVITY";
    private static final DefaultRedisScript<Long> SEAT_LOCK_SCRIPT;
    private static final DefaultRedisScript<Long> SEAT_RELEASE_SCRIPT;

    static {
        SEAT_LOCK_SCRIPT = new DefaultRedisScript<>();
        SEAT_LOCK_SCRIPT.setLocation(new ClassPathResource("lua/seat_lock.lua"));
        SEAT_LOCK_SCRIPT.setResultType(Long.class);

        SEAT_RELEASE_SCRIPT = new DefaultRedisScript<>();
        SEAT_RELEASE_SCRIPT.setLocation(new ClassPathResource("lua/seat_release.lua"));
        SEAT_RELEASE_SCRIPT.setResultType(Long.class);
    }

    private final ShowtimeService showtimeService;
    private final SeatMapper seatMapper;
    private final ShowtimeSeatMapper showtimeSeatMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public SeatLockVO lock(Long showtimeId, SeatLockRequest request) {
        SeatLockContext context = prepareLockContext(showtimeId, request);
        SeatLockVO existingLock = findIdempotentLock(context);
        if (existingLock != null) {
            return existingLock;
        }

        completeNewLockContext(context);
        SeatLockMetadata metadata = buildMetadata(context);
        String metadataJson = serializeMetadata(metadata);
        List<String> keys = buildLockKeys(context);
        long expireAtMillis = context.getExpireTime()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        Long result = stringRedisTemplate.execute(
                SEAT_LOCK_SCRIPT,
                keys,
                context.getLockToken(),
                metadataJson,
                String.valueOf(expireAtMillis),
                String.valueOf(context.getSeatIds().size())
        );
        if (Long.valueOf(0L).equals(result)) {
            return toLockVO(context);
        }
        if (Long.valueOf(1L).equals(result)) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR);
    }

    @Override
    public void release(Long showtimeId, SeatReleaseRequest request) {
        Long userId = requireUserId();
        validateShowtimeId(showtimeId);
        String lockToken = requireLockToken(request);
        String metadataJson = stringRedisTemplate.opsForValue().get(tokenKey(lockToken));
        if (metadataJson == null) {
            return;
        }

        SeatLockMetadata metadata = deserializeMetadata(metadataJson);
        validateReleaseMetadata(metadata);
        if (!Objects.equals(metadata.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.LOCK_NOT_OWNED);
        }
        if (!Objects.equals(metadata.getShowtimeId(), showtimeId)) {
            throw new BusinessException(ErrorCode.INVALID_LOCK_TOKEN);
        }

        cleanupLock(metadata.getShowtimeId(), metadata.getSeatIds(), lockToken);
    }

    @Override
    public void cleanupLock(Long showtimeId, List<Long> seatIds, String lockToken) {
        if (showtimeId == null || showtimeId <= 0
                || seatIds == null || seatIds.isEmpty()
                || seatIds.stream().anyMatch(seatId -> seatId == null || seatId <= 0)
                || lockToken == null || lockToken.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_LOCK_TOKEN);
        }
        String normalizedLockToken = lockToken.trim();
        List<String> keys = buildSeatKeys(showtimeId, seatIds);
        keys.add(0, tokenKey(normalizedLockToken));
        Long result = stringRedisTemplate.execute(
                SEAT_RELEASE_SCRIPT,
                keys,
                normalizedLockToken,
                RedisConstants.SEAT_LOCK_KEY_PREFIX
        );
        if (!Long.valueOf(0L).equals(result)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    SeatLockContext prepareLockContext(Long showtimeId, SeatLockRequest request) {
        Long userId = requireUserId();
        List<Long> seatIds = validateAndNormalizeSeatIds(request);
        ShowtimeDetailVO showtime = showtimeService.queryShowtimeDetail(showtimeId);
        if (ACTIVITY_BIZ_TYPE.equals(showtime.getBizType())) {
            showtimeService.querySeatMap(showtimeId);
        }

        validateSeats(showtime.getHallId(), seatIds);
        validateShowtimeSeats(showtimeId, seatIds);

        SeatLockContext context = new SeatLockContext();
        context.setUserId(userId);
        context.setShowtimeId(showtimeId);
        context.setHallId(showtime.getHallId());
        context.setSeatIds(seatIds);
        return context;
    }

    void completeNewLockContext(SeatLockContext context) {
        context.setLockToken(generateLockToken());
        LocalDateTime now = LocalDateTime.now();
        context.setExpireTime(now.plusMinutes(RedisConstants.SEAT_LOCK_TTL_MINUTES));
    }

    SeatLockMetadata buildMetadata(SeatLockContext context) {
        SeatLockMetadata metadata = new SeatLockMetadata();
        metadata.setUserId(context.getUserId());
        metadata.setShowtimeId(context.getShowtimeId());
        metadata.setSeatIds(new ArrayList<>(context.getSeatIds()));
        metadata.setExpireTime(context.getExpireTime());
        return metadata;
    }

    String serializeMetadata(SeatLockMetadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            log.error("Failed to serialize seat-lock metadata", exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    SeatLockMetadata deserializeMetadata(String json) {
        try {
            return objectMapper.readValue(json, SeatLockMetadata.class);
        } catch (JsonProcessingException exception) {
            log.error("Invalid seat-lock metadata JSON", exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private String generateLockToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private SeatLockVO findIdempotentLock(SeatLockContext context) {
        List<String> seatKeys = buildSeatKeys(context.getShowtimeId(), context.getSeatIds());
        List<String> lockTokens = stringRedisTemplate.opsForValue().multiGet(seatKeys);
        if (lockTokens == null || lockTokens.size() != seatKeys.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (lockTokens.stream().allMatch(Objects::isNull)) {
            return null;
        }
        if (lockTokens.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
        }

        Set<String> distinctTokens = new HashSet<>(lockTokens);
        if (distinctTokens.size() != 1) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
        }
        String lockToken = lockTokens.get(0);
        String metadataJson = stringRedisTemplate.opsForValue().get(tokenKey(lockToken));
        if (metadataJson == null) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
        }

        SeatLockMetadata metadata;
        try {
            metadata = deserializeMetadata(metadataJson);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
        }
        if (!isSameValidLock(context, metadata)) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_LOCKED);
        }

        SeatLockVO result = new SeatLockVO();
        result.setLockToken(lockToken);
        result.setExpireTime(metadata.getExpireTime());
        return result;
    }

    private boolean isSameValidLock(SeatLockContext context, SeatLockMetadata metadata) {
        return metadata != null
                && Objects.equals(metadata.getUserId(), context.getUserId())
                && Objects.equals(metadata.getShowtimeId(), context.getShowtimeId())
                && Objects.equals(metadata.getSeatIds(), context.getSeatIds())
                && metadata.getExpireTime() != null
                && metadata.getExpireTime().isAfter(LocalDateTime.now());
    }

    private List<String> buildLockKeys(SeatLockContext context) {
        List<String> keys = buildSeatKeys(context.getShowtimeId(), context.getSeatIds());
        keys.add(tokenKey(context.getLockToken()));
        return keys;
    }

    private List<String> buildSeatKeys(Long showtimeId, List<Long> seatIds) {
        List<String> keys = new ArrayList<>(seatIds.size());
        for (Long seatId : seatIds) {
            keys.add(RedisConstants.SEAT_LOCK_KEY_PREFIX + showtimeId + ":" + seatId);
        }
        return keys;
    }

    private String tokenKey(String lockToken) {
        return RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken;
    }

    private void validateShowtimeId(Long showtimeId) {
        if (showtimeId == null || showtimeId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ID);
        }
    }

    private String requireLockToken(SeatReleaseRequest request) {
        if (request == null || request.getLockToken() == null
                || request.getLockToken().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_LOCK_TOKEN);
        }
        return request.getLockToken().trim();
    }

    private void validateReleaseMetadata(SeatLockMetadata metadata) {
        if (metadata == null
                || metadata.getUserId() == null
                || metadata.getShowtimeId() == null
                || metadata.getSeatIds() == null
                || metadata.getSeatIds().isEmpty()
                || metadata.getExpireTime() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private SeatLockVO toLockVO(SeatLockContext context) {
        SeatLockVO result = new SeatLockVO();
        result.setLockToken(context.getLockToken());
        result.setExpireTime(context.getExpireTime());
        return result;
    }

    private Long requireUserId() {
        Long userId = UserHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private List<Long> validateAndNormalizeSeatIds(SeatLockRequest request) {
        if (request == null || request.getSeatIds() == null
                || request.getSeatIds().isEmpty()
                || request.getSeatIds().size() > MAX_SEATS_PER_LOCK) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }

        List<Long> normalized = new ArrayList<>(request.getSeatIds());
        Set<Long> uniqueSeatIds = new HashSet<>();
        for (Long seatId : normalized) {
            if (seatId == null || seatId <= 0 || !uniqueSeatIds.add(seatId)) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
        }
        normalized.sort(Long::compareTo);
        return normalized;
    }

    private void validateSeats(Long hallId, List<Long> seatIds) {
        List<Seat> seats = seatMapper.selectList(
                new LambdaQueryWrapper<Seat>()
                        .eq(Seat::getHallId, hallId)
                        .in(Seat::getId, seatIds)
        );
        Set<Long> foundSeatIds = seats.stream().map(Seat::getId).collect(Collectors.toSet());
        boolean allEnabled = seats.stream()
                .allMatch(seat -> Integer.valueOf(ENABLED_STATUS).equals(seat.getBaseStatus()));
        if (seats.size() != seatIds.size()
                || foundSeatIds.size() != seatIds.size()
                || !foundSeatIds.containsAll(seatIds)
                || !allEnabled) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }

    private void validateShowtimeSeats(Long showtimeId, List<Long> seatIds) {
        List<ShowtimeSeat> showtimeSeats = showtimeSeatMapper.selectList(
                new LambdaQueryWrapper<ShowtimeSeat>()
                        .eq(ShowtimeSeat::getShowtimeId, showtimeId)
                        .in(ShowtimeSeat::getSeatId, seatIds)
        );
        Set<Long> foundSeatIds = showtimeSeats.stream()
                .map(ShowtimeSeat::getSeatId)
                .collect(Collectors.toSet());
        if (showtimeSeats.size() != seatIds.size()
                || foundSeatIds.size() != seatIds.size()
                || !foundSeatIds.containsAll(seatIds)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        boolean allAvailable = showtimeSeats.stream()
                .allMatch(state -> Integer.valueOf(AVAILABLE_STATUS).equals(state.getStatus()));
        if (!allAvailable) {
            throw new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE);
        }
    }
}
