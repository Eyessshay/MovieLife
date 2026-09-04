package com.movielife.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.constants.RedisConstants;
import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.dto.SeatLockMetadata;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class TicketOrderCreationValidator {

    private static final int MAX_SEATS_PER_ORDER = 6;

    private final TicketOrderMapper ticketOrderMapper;
    private final TicketOrderItemMapper ticketOrderItemMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public TicketOrderCreationContext validate(Long userId, CreateTicketOrderDTO dto) {
        ValidatedRequest request = validateAndNormalize(userId, dto);
        TicketOrder existingOrder = findExistingOrder(request.lockToken);
        if (existingOrder != null) {
            validateExistingOrder(existingOrder, request);
            return buildContext(request, existingOrder, null);
        }

        SeatLockMetadata metadata = validateLockMetadata(request);
        validateSeatLockKeys(request);
        return buildContext(request, null, metadata);
    }

    private TicketOrder findExistingOrder(String lockToken) {
        return ticketOrderMapper.selectByLockToken(lockToken);
    }

    private void validateExistingOrder(TicketOrder order, ValidatedRequest request) {
        if (!Objects.equals(order.getUserId(), request.userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_OWNED);
        }
        if (!Objects.equals(order.getShowtimeId(), request.showtimeId)) {
            throw new BusinessException(ErrorCode.ORDER_REQUEST_MISMATCH);
        }
        if (order.getId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        List<TicketOrderItem> items = ticketOrderItemMapper.selectList(
                new LambdaQueryWrapper<TicketOrderItem>()
                        .eq(TicketOrderItem::getOrderId, order.getId())
        );
        List<Long> storedSeatIds = items.stream()
                .map(TicketOrderItem::getSeatId)
                .sorted()
                .collect(Collectors.toList());
        if (!storedSeatIds.equals(request.seatIds)) {
            throw new BusinessException(ErrorCode.ORDER_REQUEST_MISMATCH);
        }
    }

    private SeatLockMetadata validateLockMetadata(ValidatedRequest request) {
        String metadataJson = stringRedisTemplate.opsForValue().get(tokenKey(request.lockToken));
        if (metadataJson == null) {
            throw new BusinessException(ErrorCode.INVALID_LOCK_TOKEN);
        }

        SeatLockMetadata metadata = deserializeMetadata(metadataJson);
        if (metadata.getUserId() == null
                || metadata.getShowtimeId() == null
                || metadata.getSeatIds() == null
                || metadata.getExpireTime() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (!Objects.equals(metadata.getUserId(), request.userId)) {
            throw new BusinessException(ErrorCode.LOCK_NOT_OWNED);
        }
        if (!Objects.equals(metadata.getShowtimeId(), request.showtimeId)) {
            throw new BusinessException(ErrorCode.INVALID_LOCK_TOKEN);
        }

        List<Long> metadataSeatIds = validateMetadataSeatIds(metadata.getSeatIds());
        if (!metadataSeatIds.equals(request.seatIds)) {
            throw new BusinessException(ErrorCode.INVALID_LOCK_TOKEN);
        }
        if (!metadata.getExpireTime().isAfter(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.INVALID_LOCK_TOKEN);
        }
        metadata.setSeatIds(metadataSeatIds);
        return metadata;
    }

    private void validateSeatLockKeys(ValidatedRequest request) {
        List<String> seatKeys = request.seatIds.stream()
                .map(seatId -> RedisConstants.SEAT_LOCK_KEY_PREFIX
                        + request.showtimeId + ":" + seatId)
                .collect(Collectors.toList());
        List<String> values = stringRedisTemplate.opsForValue().multiGet(seatKeys);
        if (values == null || values.size() != seatKeys.size()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        for (String value : values) {
            if (!request.lockToken.equals(value)) {
                throw new BusinessException(ErrorCode.INVALID_LOCK_TOKEN);
            }
        }
    }

    private SeatLockMetadata deserializeMetadata(String json) {
        try {
            return objectMapper.readValue(json, SeatLockMetadata.class);
        } catch (JsonProcessingException exception) {
            log.error("Invalid seat-lock metadata JSON during order validation", exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private List<Long> validateMetadataSeatIds(List<Long> seatIds) {
        if (seatIds.isEmpty() || seatIds.size() > MAX_SEATS_PER_ORDER) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        List<Long> normalized = new ArrayList<>(seatIds);
        Set<Long> uniqueSeatIds = new HashSet<>();
        for (Long seatId : normalized) {
            if (seatId == null || seatId <= 0 || !uniqueSeatIds.add(seatId)) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
        }
        return normalized;
    }

    private ValidatedRequest validateAndNormalize(Long userId, CreateTicketOrderDTO dto) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (dto == null || dto.getShowtimeId() == null || dto.getShowtimeId() <= 0
                || dto.getSeatIds() == null || dto.getSeatIds().isEmpty()
                || dto.getSeatIds().size() > MAX_SEATS_PER_ORDER
                || dto.getLockToken() == null || dto.getLockToken().trim().isEmpty()
                || dto.getLockToken().length() > 64) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }

        if (dto.getSeatIds().stream().anyMatch(seatId -> seatId == null || seatId <= 0)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        List<Long> normalizedSeatIds = dto.getSeatIds().stream()
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        if (normalizedSeatIds.size() != dto.getSeatIds().size()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        return new ValidatedRequest(
                userId,
                dto.getShowtimeId(),
                normalizedSeatIds,
                dto.getLockToken().trim()
        );
    }

    private TicketOrderCreationContext buildContext(ValidatedRequest request,
                                                     TicketOrder existingOrder,
                                                     SeatLockMetadata metadata) {
        TicketOrderCreationContext context = new TicketOrderCreationContext();
        context.setUserId(request.userId);
        context.setShowtimeId(request.showtimeId);
        context.setSeatIds(new ArrayList<>(request.seatIds));
        context.setLockToken(request.lockToken);
        context.setExistingOrder(existingOrder);
        context.setLockMetadata(metadata);
        return context;
    }

    private String tokenKey(String lockToken) {
        return RedisConstants.SEAT_LOCK_TOKEN_KEY_PREFIX + lockToken;
    }

    private static final class ValidatedRequest {

        private final Long userId;
        private final Long showtimeId;
        private final List<Long> seatIds;
        private final String lockToken;

        private ValidatedRequest(Long userId,
                                 Long showtimeId,
                                 List<Long> seatIds,
                                 String lockToken) {
            this.userId = userId;
            this.showtimeId = showtimeId;
            this.seatIds = seatIds;
            this.lockToken = lockToken;
        }
    }
}
