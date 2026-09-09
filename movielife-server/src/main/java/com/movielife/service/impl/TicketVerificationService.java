package com.movielife.service.impl;

import com.movielife.entity.Seat;
import com.movielife.entity.Showtime;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.enums.TicketOrderStatus;
import com.movielife.enums.TicketStatus;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.SeatMapper;
import com.movielife.mapper.ShowtimeMapper;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import com.movielife.mapper.VenueStaffMapper;
import com.movielife.service.TicketVerificationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class TicketVerificationService {

    private static final int MAX_TICKET_CODE_LENGTH = 64;

    private final TicketOrderMapper ticketOrderMapper;
    private final TicketOrderItemMapper ticketOrderItemMapper;
    private final ShowtimeMapper showtimeMapper;
    private final SeatMapper seatMapper;
    private final VenueStaffMapper venueStaffMapper;

    @Transactional
    public TicketVerificationResult verifyTicket(Long userId, String ticketCode) {
        String normalizedTicketCode = validateRequest(userId, ticketCode);
        TicketOrderItem locatedItem = ticketOrderItemMapper.selectByTicketCode(normalizedTicketCode);
        if (locatedItem == null) {
            throw new BusinessException(ErrorCode.TICKET_NOT_FOUND);
        }
        if (locatedItem.getId() == null || locatedItem.getOrderId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        TicketOrder order = ticketOrderMapper.selectByIdForUpdate(locatedItem.getOrderId());
        if (order == null || order.getId() == null || order.getShowtimeId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        TicketOrderItem item = ticketOrderItemMapper.selectByIdAndOrderIdForUpdate(
                locatedItem.getId(), order.getId());
        if (item == null || !normalizedTicketCode.equals(item.getTicketCode())
                || !order.getId().equals(item.getOrderId()) || item.getSeatId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        Showtime showtime = showtimeMapper.selectById(order.getShowtimeId());
        if (showtime == null || showtime.getId() == null || showtime.getVenueId() == null
                || showtime.getHallId() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        if (venueStaffMapper.selectActiveIdForShare(userId, showtime.getVenueId()) == null) {
            throw new BusinessException(ErrorCode.TICKET_VERIFY_FORBIDDEN);
        }

        validateUsageEvidence(item);
        if (TicketStatus.REFUNDED.getCode() == item.getTicketStatus()) {
            throw new BusinessException(ErrorCode.TICKET_REFUNDED);
        }
        if (!TicketOrderStatus.PAID.getDbValue().equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT);
        }
        if (TicketStatus.PENDING.getCode() == item.getTicketStatus()) {
            throw new BusinessException(ErrorCode.TICKET_NOT_ISSUED);
        }
        if (TicketStatus.USED.getCode() == item.getTicketStatus()) {
            throw new BusinessException(ErrorCode.TICKET_ALREADY_USED);
        }
        if (TicketStatus.VALID.getCode() != item.getTicketStatus()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        Seat seat = seatMapper.selectById(item.getSeatId());
        if (seat == null || seat.getId() == null || seat.getHallId() == null
                || !showtime.getHallId().equals(seat.getHallId())
                || seat.getSeatNo() == null || seat.getSeatNo().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        LocalDateTime usedTime = LocalDateTime.now().withNano(0);
        if (ticketOrderItemMapper.markUsed(item.getId(), order.getId(),
                TicketStatus.VALID.getCode(), TicketStatus.USED.getCode(),
                usedTime, userId) != 1) {
            TicketOrderItem latest = ticketOrderItemMapper.selectByIdAndOrderIdForUpdate(
                    item.getId(), order.getId());
            throwFinalState(latest);
        }

        return result(normalizedTicketCode, usedTime, order, showtime, item, seat);
    }

    private String validateRequest(Long userId, String ticketCode) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (ticketCode == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        String normalized = ticketCode.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_TICKET_CODE_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        return normalized;
    }

    private void validateUsageEvidence(TicketOrderItem item) {
        if (item.getTicketStatus() == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        boolean used = TicketStatus.USED.getCode() == item.getTicketStatus();
        boolean completeEvidence = item.getUsedTime() != null && item.getUsedByUserId() != null;
        boolean noEvidence = item.getUsedTime() == null && item.getUsedByUserId() == null;
        if ((used && !completeEvidence) || (!used && !noEvidence)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void throwFinalState(TicketOrderItem item) {
        if (item == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        validateUsageEvidence(item);
        if (TicketStatus.USED.getCode() == item.getTicketStatus()) {
            throw new BusinessException(ErrorCode.TICKET_ALREADY_USED);
        }
        if (TicketStatus.REFUNDED.getCode() == item.getTicketStatus()) {
            throw new BusinessException(ErrorCode.TICKET_REFUNDED);
        }
        if (TicketStatus.PENDING.getCode() == item.getTicketStatus()) {
            throw new BusinessException(ErrorCode.TICKET_NOT_ISSUED);
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR);
    }

    private TicketVerificationResult result(String ticketCode, LocalDateTime usedTime,
                                            TicketOrder order, Showtime showtime,
                                            TicketOrderItem item, Seat seat) {
        TicketVerificationResult result = new TicketVerificationResult();
        result.setTicketCode(ticketCode);
        result.setTicketStatus(TicketStatus.USED.getCode());
        result.setUsedTime(usedTime);
        result.setOrderId(order.getId());
        result.setShowtimeId(showtime.getId());
        result.setVenueId(showtime.getVenueId());
        result.setHallId(showtime.getHallId());
        result.setSeatId(item.getSeatId());
        result.setSeatNo(seat.getSeatNo());
        return result;
    }
}
