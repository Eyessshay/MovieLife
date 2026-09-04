package com.movielife.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.dto.SeatLockMetadata;
import com.movielife.entity.TicketOrder;
import com.movielife.entity.TicketOrderItem;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.mapper.TicketOrderItemMapper;
import com.movielife.mapper.TicketOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketOrderCreationValidatorTest {

    private static final Long USER_ID = 1L;
    private static final Long SHOWTIME_ID = 1001L;
    private static final String LOCK_TOKEN = "0123456789abcdef0123456789abcdef";

    @Mock
    private TicketOrderMapper ticketOrderMapper;
    @Mock
    private TicketOrderItemMapper ticketOrderItemMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private TicketOrderCreationValidator validator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        validator = new TicketOrderCreationValidator(
                ticketOrderMapper,
                ticketOrderItemMapper,
                objectMapper,
                stringRedisTemplate
        );
    }

    @Test
    void duplicateSeatIdsShouldFailBeforeMySqlOrRedis() {
        assertError(ErrorCode.INVALID_PARAM,
                () -> validator.validate(USER_ID, request(101L, 101L)));

        verify(ticketOrderMapper, never()).selectByLockToken(any());
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void consumedTokenWithSameRequestShouldReturnExistingOrderWithoutRedis() {
        TicketOrder order = existingOrder(USER_ID, SHOWTIME_ID);
        order.setStatus("REFUNDED");
        when(ticketOrderMapper.selectByLockToken(any())).thenReturn(order);
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Arrays.asList(item(102L), item(101L)));

        TicketOrderCreationContext context = validator.validate(USER_ID, request(102L, 101L));

        assertThat(context.isConsumed()).isTrue();
        assertThat(context.getExistingOrder()).isSameAs(order);
        assertThat(context.getSeatIds()).containsExactly(101L, 102L);
        assertThat(context.getLockMetadata()).isNull();
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void consumedTokenOwnedByAnotherUserShouldFailWithoutItemsOrRedis() {
        when(ticketOrderMapper.selectByLockToken(any())).thenReturn(existingOrder(2L, SHOWTIME_ID));

        assertError(ErrorCode.ORDER_NOT_OWNED,
                () -> validator.validate(USER_ID, request(101L, 102L)));

        verify(ticketOrderItemMapper, never()).selectList(any());
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void consumedTokenWithDifferentShowtimeShouldFailWithoutRedis() {
        when(ticketOrderMapper.selectByLockToken(any())).thenReturn(existingOrder(USER_ID, 2002L));

        assertError(ErrorCode.ORDER_REQUEST_MISMATCH,
                () -> validator.validate(USER_ID, request(101L, 102L)));

        verify(ticketOrderItemMapper, never()).selectList(any());
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void consumedTokenWithDifferentSeatsShouldFailWithoutRedis() {
        when(ticketOrderMapper.selectByLockToken(any())).thenReturn(existingOrder(USER_ID, SHOWTIME_ID));
        when(ticketOrderItemMapper.selectList(any())).thenReturn(Collections.singletonList(item(101L)));

        assertError(ErrorCode.ORDER_REQUEST_MISMATCH,
                () -> validator.validate(USER_ID, request(101L, 102L)));

        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void missingMetadataShouldBeInvalidLockToken() {
        stubRedis();
        when(valueOperations.get(tokenKey())).thenReturn(null);

        assertError(ErrorCode.INVALID_LOCK_TOKEN,
                () -> validator.validate(USER_ID, request(101L, 102L)));

        verify(valueOperations, never()).multiGet(anyList());
    }

    @Test
    void metadataOwnedByAnotherUserShouldFail() throws Exception {
        stubMetadata(metadata(2L, SHOWTIME_ID, future(), 101L, 102L));

        assertError(ErrorCode.LOCK_NOT_OWNED,
                () -> validator.validate(USER_ID, request(101L, 102L)));
    }

    @Test
    void metadataForDifferentShowtimeShouldFail() throws Exception {
        stubMetadata(metadata(USER_ID, 2002L, future(), 101L, 102L));

        assertError(ErrorCode.INVALID_LOCK_TOKEN,
                () -> validator.validate(USER_ID, request(101L, 102L)));
    }

    @Test
    void metadataWithDifferentSeatsShouldFail() throws Exception {
        stubMetadata(metadata(USER_ID, SHOWTIME_ID, future(), 101L));

        assertError(ErrorCode.INVALID_LOCK_TOKEN,
                () -> validator.validate(USER_ID, request(101L, 102L)));
    }

    @Test
    void metadataSeatOrderMustAlreadyBeNormalized() throws Exception {
        stubMetadata(metadata(USER_ID, SHOWTIME_ID, future(), 102L, 101L));

        assertError(ErrorCode.INVALID_LOCK_TOKEN,
                () -> validator.validate(USER_ID, request(101L, 102L)));
    }

    @Test
    void expiredMetadataShouldFail() throws Exception {
        stubMetadata(metadata(USER_ID, SHOWTIME_ID, LocalDateTime.now().minusSeconds(1), 101L, 102L));

        assertError(ErrorCode.INVALID_LOCK_TOKEN,
                () -> validator.validate(USER_ID, request(101L, 102L)));
    }

    @Test
    void missingSeatKeyShouldFailWholeRequest() throws Exception {
        stubMetadata(metadata(USER_ID, SHOWTIME_ID, future(), 101L, 102L));
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(LOCK_TOKEN, null));

        assertError(ErrorCode.INVALID_LOCK_TOKEN,
                () -> validator.validate(USER_ID, request(101L, 102L)));
    }

    @Test
    void seatKeyOwnedByAnotherTokenShouldFailWholeRequest() throws Exception {
        stubMetadata(metadata(USER_ID, SHOWTIME_ID, future(), 101L, 102L));
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(LOCK_TOKEN, "other-token"));

        assertError(ErrorCode.INVALID_LOCK_TOKEN,
                () -> validator.validate(USER_ID, request(101L, 102L)));
    }

    @Test
    void completeUnconsumedLockShouldReturnNormalizedReusableContext() throws Exception {
        SeatLockMetadata metadata = metadata(USER_ID, SHOWTIME_ID, future(), 101L, 102L);
        stubMetadata(metadata);
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(LOCK_TOKEN, LOCK_TOKEN));

        TicketOrderCreationContext context = validator.validate(USER_ID, request(102L, 101L));

        assertThat(context.isConsumed()).isFalse();
        assertThat(context.getExistingOrder()).isNull();
        assertThat(context.getLockMetadata().getUserId()).isEqualTo(metadata.getUserId());
        assertThat(context.getLockMetadata().getShowtimeId()).isEqualTo(metadata.getShowtimeId());
        assertThat(context.getLockMetadata().getSeatIds()).containsExactly(101L, 102L);
        assertThat(context.getLockMetadata().getExpireTime()).isEqualTo(metadata.getExpireTime());
        assertThat(context.getSeatIds()).containsExactly(101L, 102L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(valueOperations).multiGet(keys.capture());
        assertThat(keys.getValue()).containsExactly(
                "seat:lock:" + SHOWTIME_ID + ":101",
                "seat:lock:" + SHOWTIME_ID + ":102"
        );
    }

    private void stubRedis() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private void stubMetadata(SeatLockMetadata metadata) throws Exception {
        stubRedis();
        when(valueOperations.get(tokenKey())).thenReturn(objectMapper.writeValueAsString(metadata));
    }

    private String tokenKey() {
        return "seat:lock:token:" + LOCK_TOKEN;
    }

    private LocalDateTime future() {
        return LocalDateTime.now().plusMinutes(5);
    }

    private SeatLockMetadata metadata(Long userId,
                                      Long showtimeId,
                                      LocalDateTime expireTime,
                                      Long... seatIds) {
        SeatLockMetadata metadata = new SeatLockMetadata();
        metadata.setUserId(userId);
        metadata.setShowtimeId(showtimeId);
        metadata.setSeatIds(Arrays.asList(seatIds));
        metadata.setExpireTime(expireTime);
        return metadata;
    }

    private TicketOrder existingOrder(Long userId, Long showtimeId) {
        TicketOrder order = new TicketOrder();
        order.setId(9001L);
        order.setUserId(userId);
        order.setShowtimeId(showtimeId);
        order.setLockToken(LOCK_TOKEN);
        order.setStatus("UNPAID");
        return order;
    }

    private TicketOrderItem item(Long seatId) {
        TicketOrderItem item = new TicketOrderItem();
        item.setOrderId(9001L);
        item.setSeatId(seatId);
        return item;
    }

    private CreateTicketOrderDTO request(Long... seatIds) {
        CreateTicketOrderDTO dto = new CreateTicketOrderDTO();
        dto.setShowtimeId(SHOWTIME_ID);
        dto.setSeatIds(Arrays.asList(seatIds));
        dto.setLockToken(LOCK_TOKEN);
        return dto;
    }

    private void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
