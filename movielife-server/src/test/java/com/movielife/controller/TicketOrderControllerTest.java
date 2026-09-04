package com.movielife.controller;

import com.movielife.constants.RedisConstants;
import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.service.TicketOrderService;
import com.movielife.vo.CreateTicketOrderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TicketOrderController.class)
class TicketOrderControllerTest {

    private static final String AUTHORIZATION = "Bearer order-controller-test-token";
    private static final String VALID_REQUEST = "{\"showtimeId\":1001,"
            + "\"seatIds\":[101,102],\"lockToken\":\"lock-token\"}";

    @Autowired private MockMvc mockMvc;
    @MockBean private TicketOrderService ticketOrderService;
    @MockBean private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpAuthentication() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisConstants.LOGIN_TOKEN_KEY + "order-controller-test-token"))
                .thenReturn("1");
    }

    @Test
    void shouldCreateOrderForCurrentAuthenticatedUser() throws Exception {
        CreateTicketOrderVO result = new CreateTicketOrderVO();
        result.setOrderId(9001L);
        result.setStatus("UNPAID");
        result.setTotalAmount(new BigDecimal("95.00"));
        result.setExpireTime(LocalDateTime.of(2026, 9, 4, 16, 30));
        when(ticketOrderService.createOrder(eq(1L), any(CreateTicketOrderDTO.class)))
                .thenReturn(result);

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.orderId").value(9001))
                .andExpect(jsonPath("$.data.status").value("UNPAID"))
                .andExpect(jsonPath("$.data.totalAmount").value(95.00))
                .andExpect(jsonPath("$.data.expireTime").value("2026-09-04T16:30:00"))
                .andExpect(jsonPath("$.requestId").exists());

        org.mockito.ArgumentCaptor<CreateTicketOrderDTO> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(CreateTicketOrderDTO.class);
        verify(ticketOrderService).createOrder(eq(1L), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getShowtimeId()).isEqualTo(1001L);
        assertThat(requestCaptor.getValue().getSeatIds()).containsExactly(101L, 102L);
        assertThat(requestCaptor.getValue().getLockToken()).isEqualTo("lock-token");
    }

    @Test
    void shouldRejectUnauthenticatedRequestBeforeService() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(ticketOrderService, never()).createOrder(any(), any());
    }

    @Test
    void shouldRejectMissingBody() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void shouldRejectInvalidShowtimeIds() throws Exception {
        assertBadRequest("{\"seatIds\":[101],\"lockToken\":\"lock-token\"}");
        assertBadRequest("{\"showtimeId\":0,\"seatIds\":[101],\"lockToken\":\"lock-token\"}");
    }

    @Test
    void shouldRejectInvalidSeatLists() throws Exception {
        assertBadRequest("{\"showtimeId\":1001,\"lockToken\":\"lock-token\"}");
        assertBadRequest("{\"showtimeId\":1001,\"seatIds\":[],\"lockToken\":\"lock-token\"}");
        assertBadRequest("{\"showtimeId\":1001,\"seatIds\":[1,2,3,4,5,6,7],"
                + "\"lockToken\":\"lock-token\"}");
        assertBadRequest("{\"showtimeId\":1001,\"seatIds\":[0],\"lockToken\":\"lock-token\"}");
    }

    @Test
    void shouldRejectInvalidLockTokens() throws Exception {
        assertBadRequest("{\"showtimeId\":1001,\"seatIds\":[101]}");
        assertBadRequest("{\"showtimeId\":1001,\"seatIds\":[101],\"lockToken\":\"\"}");
        assertBadRequest("{\"showtimeId\":1001,\"seatIds\":[101],"
                + "\"lockToken\":\"12345678901234567890123456789012345678901234567890123456789012345\"}");
    }

    @Test
    void duplicateSeatsRejectedByServiceShouldRemainBadRequest() throws Exception {
        doThrow(new BusinessException(ErrorCode.INVALID_PARAM))
                .when(ticketOrderService).createOrder(eq(1L), any(CreateTicketOrderDTO.class));

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showtimeId\":1001,\"seatIds\":[101,101],"
                                + "\"lockToken\":\"lock-token\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    @Test
    void shouldMapConsumedOrderOwnershipAndMismatchErrors() throws Exception {
        doThrow(new BusinessException(ErrorCode.ORDER_NOT_OWNED))
                .doThrow(new BusinessException(ErrorCode.ORDER_REQUEST_MISMATCH))
                .when(ticketOrderService).createOrder(eq(1L), any(CreateTicketOrderDTO.class));

        assertBusinessError(403, "ORDER_NOT_OWNED");
        assertBusinessError(409, "ORDER_REQUEST_MISMATCH");
    }

    @Test
    void shouldMapLockSeatAndShowtimeErrors() throws Exception {
        doThrow(new BusinessException(ErrorCode.INVALID_LOCK_TOKEN))
                .doThrow(new BusinessException(ErrorCode.LOCK_NOT_OWNED))
                .doThrow(new BusinessException(ErrorCode.SEAT_NOT_AVAILABLE))
                .doThrow(new BusinessException(ErrorCode.SHOWTIME_NOT_FOUND))
                .doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR))
                .when(ticketOrderService).createOrder(eq(1L), any(CreateTicketOrderDTO.class));

        assertBusinessError(400, "INVALID_LOCK_TOKEN");
        assertBusinessError(403, "LOCK_NOT_OWNED");
        assertBusinessError(409, "SEAT_NOT_AVAILABLE");
        assertBusinessError(404, "SHOWTIME_NOT_FOUND");
        assertBusinessError(500, "INTERNAL_ERROR");
    }

    private void assertBadRequest(String body) throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    private void assertBusinessError(int statusCode, String code) throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.code").value(code));
    }
}
