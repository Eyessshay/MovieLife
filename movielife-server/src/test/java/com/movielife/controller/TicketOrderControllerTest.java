package com.movielife.controller;

import com.movielife.constants.RedisConstants;
import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.service.TicketOrderService;
import com.movielife.vo.CreateTicketOrderVO;
import com.movielife.vo.PageVO;
import com.movielife.vo.PayTicketOrderVO;
import com.movielife.vo.TicketOrderDetailVO;
import com.movielife.vo.TicketOrderItemVO;
import com.movielife.vo.TicketOrderListItemVO;
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
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void shouldQueryCurrentUserOrdersWithDefaultPagination() throws Exception {
        TicketOrderListItemVO item = new TicketOrderListItemVO();
        item.setOrderId(9001L);
        item.setMovieName("Movie A");
        item.setVenueName("Venue A");
        item.setHallName("Hall A");
        item.setStatus("UNPAID");
        item.setTotalAmount(new BigDecimal("95.00"));
        item.setSeatCount(2);
        when(ticketOrderService.queryMyOrders(1L, 1, 20))
                .thenReturn(new PageVO<>(Collections.singletonList(item), 1L, 1, 20));

        mockMvc.perform(get("/api/orders").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.list[0].orderId").value(9001))
                .andExpect(jsonPath("$.data.list[0].seatCount").value(2));

        verify(ticketOrderService).queryMyOrders(1L, 1, 20);
    }

    @Test
    void emptyOrderPageShouldReturnEmptyList() throws Exception {
        when(ticketOrderService.queryMyOrders(1L, 2, 10))
                .thenReturn(new PageVO<>(Collections.emptyList(), 0L, 2, 10));

        mockMvc.perform(get("/api/orders")
                        .header("Authorization", AUTHORIZATION)
                        .param("page", "2")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void orderQueriesShouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/orders/9001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(ticketOrderService, never()).queryMyOrders(any(), any(), any());
        verify(ticketOrderService, never()).queryOrderDetail(any(), any());
    }

    @Test
    void invalidOrderPaginationShouldReturnBadRequest() throws Exception {
        doThrow(new BusinessException(ErrorCode.INVALID_PAGE))
                .when(ticketOrderService).queryMyOrders(1L, 0, 20);
        doThrow(new BusinessException(ErrorCode.PAGE_SIZE_TOO_LARGE))
                .when(ticketOrderService).queryMyOrders(1L, 1, 101);

        mockMvc.perform(get("/api/orders")
                        .header("Authorization", AUTHORIZATION).param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", AUTHORIZATION).param("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_SIZE_TOO_LARGE"));
    }

    @Test
    void shouldReturnCurrentUserOrderDetail() throws Exception {
        TicketOrderItemVO item = new TicketOrderItemVO();
        item.setSeatId(101L);
        item.setRowNo(1);
        item.setColNo(2);
        item.setSeatNo("A2");
        item.setPrice(new BigDecimal("40.00"));
        item.setTicketStatus(0);
        TicketOrderDetailVO detail = new TicketOrderDetailVO();
        detail.setOrderId(9001L);
        detail.setStatus("UNPAID");
        detail.setItems(Arrays.asList(item));
        when(ticketOrderService.queryOrderDetail(1L, 9001L)).thenReturn(detail);

        mockMvc.perform(get("/api/orders/9001").header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(9001))
                .andExpect(jsonPath("$.data.status").value("UNPAID"))
                .andExpect(jsonPath("$.data.items[0].seatNo").value("A2"))
                .andExpect(jsonPath("$.data.items[0].price").value(40.00));

        verify(ticketOrderService).queryOrderDetail(1L, 9001L);
    }

    @Test
    void missingOrForeignOrderShouldReturnNotFoundAndInvalidIdShouldBeBadRequest()
            throws Exception {
        doThrow(new BusinessException(ErrorCode.ORDER_NOT_FOUND))
                .when(ticketOrderService).queryOrderDetail(1L, 9001L);
        doThrow(new BusinessException(ErrorCode.INVALID_ID))
                .when(ticketOrderService).queryOrderDetail(1L, 0L);

        mockMvc.perform(get("/api/orders/9001").header("Authorization", AUTHORIZATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(get("/api/orders/0").header("Authorization", AUTHORIZATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ID"));
    }

    @Test
    void shouldCancelCurrentUsersUnpaidOrderAndAllowIdempotentRetry() throws Exception {
        mockMvc.perform(post("/api/orders/9001/cancel")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());
        mockMvc.perform(post("/api/orders/9001/cancel")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        verify(ticketOrderService, org.mockito.Mockito.times(2)).cancelOrder(1L, 9001L);
    }

    @Test
    void cancellationShouldRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/orders/9001/cancel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(ticketOrderService, never()).cancelOrder(any(), any());
    }

    @Test
    void invalidCancellationOrderIdShouldReturnBadRequest() throws Exception {
        doThrow(new BusinessException(ErrorCode.INVALID_ID))
                .when(ticketOrderService).cancelOrder(1L, 0L);

        mockMvc.perform(post("/api/orders/0/cancel")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ID"));
    }

    @Test
    void missingForeignAndPaidCancellationShouldUseStableErrors() throws Exception {
        doThrow(new BusinessException(ErrorCode.ORDER_NOT_FOUND))
                .when(ticketOrderService).cancelOrder(1L, 9001L);
        doThrow(new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT))
                .when(ticketOrderService).cancelOrder(1L, 9002L);

        mockMvc.perform(post("/api/orders/9001/cancel")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(post("/api/orders/9002/cancel")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_CONFLICT"));
    }

    @Test
    void shouldPayCurrentUsersOrderAndReturnPaymentResult() throws Exception {
        PayTicketOrderVO paid = new PayTicketOrderVO();
        paid.setOrderId(9001L);
        paid.setStatus("PAID");
        paid.setPayTime(LocalDateTime.of(2026, 9, 7, 11, 0));
        when(ticketOrderService.payOrder(1L, 9001L)).thenReturn(paid);

        mockMvc.perform(post("/api/orders/9001/pay")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.orderId").value(9001))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.payTime").value("2026-09-07T11:00:00"));

        verify(ticketOrderService).payOrder(1L, 9001L);
    }

    @Test
    void paymentShouldRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/orders/9001/pay"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(ticketOrderService, never()).payOrder(any(), any());
    }

    @Test
    void invalidPaymentOrderIdShouldReturnBadRequest() throws Exception {
        doThrow(new BusinessException(ErrorCode.INVALID_ID))
                .when(ticketOrderService).payOrder(1L, 0L);

        mockMvc.perform(post("/api/orders/0/pay")
                        .header("Authorization", AUTHORIZATION))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ID"));
    }

    @Test
    void paymentBusinessErrorsShouldUseStableHttpMappings() throws Exception {
        doThrow(new BusinessException(ErrorCode.ORDER_NOT_FOUND))
                .when(ticketOrderService).payOrder(1L, 9001L);
        doThrow(new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT))
                .when(ticketOrderService).payOrder(1L, 9002L);
        doThrow(new BusinessException(ErrorCode.ORDER_EXPIRED))
                .when(ticketOrderService).payOrder(1L, 9003L);

        mockMvc.perform(post("/api/orders/9001/pay").header("Authorization", AUTHORIZATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(post("/api/orders/9002/pay").header("Authorization", AUTHORIZATION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_CONFLICT"));
        mockMvc.perform(post("/api/orders/9003/pay").header("Authorization", AUTHORIZATION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_EXPIRED"));
    }

    @Test
    void issuanceShouldRequireAuthenticationAndMapErrors() throws Exception {
        mockMvc.perform(post("/api/orders/1/issue")).andExpect(status().isUnauthorized());
        verify(ticketOrderService, never()).issueTickets(any(), any());
        doThrow(new BusinessException(ErrorCode.INVALID_ID)).when(ticketOrderService).issueTickets(1L, 0L);
        doThrow(new BusinessException(ErrorCode.ORDER_NOT_FOUND)).when(ticketOrderService).issueTickets(1L, 1L);
        doThrow(new BusinessException(ErrorCode.ORDER_STATUS_CONFLICT)).when(ticketOrderService).issueTickets(1L, 2L);
        mockMvc.perform(post("/api/orders/0/issue").header("Authorization", AUTHORIZATION))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/orders/1/issue").header("Authorization", AUTHORIZATION))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mockMvc.perform(post("/api/orders/2/issue").header("Authorization", AUTHORIZATION))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_STATUS_CONFLICT"));
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
