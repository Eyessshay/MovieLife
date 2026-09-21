package com.movielife.service.impl;

import com.movielife.dto.CreateActivityShowtimeDTO;
import com.movielife.entity.Showtime;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import lombok.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

@Value
class ActivityShowtimeCreationCommand {

    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    String requestId;
    Long activityId;
    Long hallId;
    LocalDateTime startTime;
    LocalDateTime endTime;
    BigDecimal basePrice;

    static ActivityShowtimeCreationCommand from(CreateActivityShowtimeDTO request) {
        if (request == null || request.getRequestId() == null
                || !REQUEST_ID_PATTERN.matcher(request.getRequestId()).matches()
                || request.getActivityId() == null || request.getActivityId() <= 0
                || request.getHallId() == null || request.getHallId() <= 0
                || request.getStartTime() == null || request.getEndTime() == null
                || request.getBasePrice() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        LocalDateTime startTime = request.getStartTime().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime endTime = request.getEndTime().truncatedTo(ChronoUnit.SECONDS);
        if (!endTime.isAfter(startTime)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }

        BigDecimal basePrice;
        try {
            basePrice = request.getBasePrice().setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        if (basePrice.signum() <= 0 || basePrice.compareTo(MAX_PRICE) > 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        return new ActivityShowtimeCreationCommand(request.getRequestId(), request.getActivityId(),
                request.getHallId(), startTime, endTime, basePrice);
    }

    boolean matches(Showtime showtime, Long venueId) {
        return showtime != null
                && venueId.equals(showtime.getVenueId())
                && "ACTIVITY".equals(showtime.getBizType())
                && requestId.equals(showtime.getCreateRequestId())
                && activityId.equals(showtime.getBizId())
                && hallId.equals(showtime.getHallId())
                && startTime.equals(showtime.getStartTime())
                && endTime.equals(showtime.getEndTime())
                && showtime.getBasePrice() != null
                && basePrice.compareTo(showtime.getBasePrice()) == 0;
    }
}
