package com.movielife.controller;

import com.movielife.constants.RedisConstants;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import com.movielife.service.TicketVerificationResult;
import com.movielife.service.impl.TicketVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

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

@WebMvcTest(TicketVerificationController.class)
class TicketVerificationControllerTest {

    private static final String AUTHORIZATION = "Bearer ticket-verification-controller-token";
    private static final String TICKET_CODE = "ticket-code-1";
    private static final String VALID_REQUEST = "{\"ticketCode\":\"" + TICKET_CODE + "\"}";

    @Autowired private MockMvc mockMvc;
    @MockBean private TicketVerificationService ticketVerificationService;
    @MockBean private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpAuthentication() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisConstants.LOGIN_TOKEN_KEY + "ticket-verification-controller-token"))
                .thenReturn("7");
    }

    @Test
    void shouldVerifyTicketForCurrentAuthenticatedUserAndMapResponse() throws Exception {
        LocalDateTime usedTime = LocalDateTime.of(2026, 9, 10, 18, 40);
        TicketVerificationResult result = result(usedTime);
        when(ticketVerificationService.verifyTicket(7L, TICKET_CODE)).thenReturn(result);

        mockMvc.perform(post("/api/admin/tickets/verify")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketCode\":\"  " + TICKET_CODE + "  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.ticketCode").value(TICKET_CODE))
                .andExpect(jsonPath("$.data.ticketStatus").value(2))
                .andExpect(jsonPath("$.data.usedTime").value("2026-09-10T18:40:00"))
                .andExpect(jsonPath("$.data.orderId").value(9001))
                .andExpect(jsonPath("$.data.showtimeId").value(1001))
                .andExpect(jsonPath("$.data.venueId").value(501))
                .andExpect(jsonPath("$.data.hallId").value(601))
                .andExpect(jsonPath("$.data.seatId").value(101))
                .andExpect(jsonPath("$.data.seatNo").value("A01"));

        verify(ticketVerificationService).verifyTicket(7L, TICKET_CODE);
    }

    @Test
    void shouldRequireAuthenticationBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/admin/tickets/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(ticketVerificationService, never()).verifyTicket(any(), any());
    }

    @Test
    void shouldRejectMissingBlankAndOversizedTicketCodes() throws Exception {
        assertBadRequest("{}");
        assertBadRequest("{\"ticketCode\":\"   \"}");
        assertBadRequest("{\"ticketCode\":\"" + repeat('x', 65) + "\"}");
    }

    @Test
    void shouldMapStableVerificationBusinessErrors() throws Exception {
        assertBusinessError(ErrorCode.TICKET_VERIFY_FORBIDDEN);
        assertBusinessError(ErrorCode.TICKET_NOT_FOUND);
        assertBusinessError(ErrorCode.TICKET_NOT_ISSUED);
        assertBusinessError(ErrorCode.TICKET_ALREADY_USED);
        assertBusinessError(ErrorCode.TICKET_REFUNDED);
        assertBusinessError(ErrorCode.ORDER_STATUS_CONFLICT);
    }

    private void assertBadRequest(String body) throws Exception {
        mockMvc.perform(post("/api/admin/tickets/verify")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    private void assertBusinessError(ErrorCode errorCode) throws Exception {
        org.mockito.Mockito.reset(ticketVerificationService);
        doThrow(new BusinessException(errorCode)).when(ticketVerificationService)
                .verifyTicket(eq(7L), eq(TICKET_CODE));
        mockMvc.perform(post("/api/admin/tickets/verify")
                        .header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().is(errorCode.getHttpStatus().value()))
                .andExpect(jsonPath("$.code").value(errorCode.getCode()));
    }

    private TicketVerificationResult result(LocalDateTime usedTime) {
        TicketVerificationResult result = new TicketVerificationResult();
        result.setTicketCode(TICKET_CODE);
        result.setTicketStatus(2);
        result.setUsedTime(usedTime);
        result.setOrderId(9001L);
        result.setShowtimeId(1001L);
        result.setVenueId(501L);
        result.setHallId(601L);
        result.setSeatId(101L);
        result.setSeatNo("A01");
        return result;
    }

    private String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
