package com.movielife.service;

import com.movielife.dto.CreateTicketOrderDTO;
import com.movielife.exception.ErrorCode;
import com.movielife.vo.CreateTicketOrderVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TicketOrderContractTest {

    @Test
    void shouldFreezeServiceResponseAndMismatchErrors() throws Exception {
        Method createOrder = TicketOrderService.class.getMethod(
                "createOrder", Long.class, CreateTicketOrderDTO.class);
        assertThat(createOrder.getReturnType()).isEqualTo(CreateTicketOrderVO.class);

        assertThat(Arrays.stream(CreateTicketOrderVO.class.getDeclaredFields())
                .map(Field::getName))
                .containsExactlyInAnyOrder("orderId", "status", "totalAmount", "expireTime");

        assertThat(ErrorCode.ORDER_NOT_OWNED.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ErrorCode.ORDER_REQUEST_MISMATCH.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
    }
}
