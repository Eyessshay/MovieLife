package com.movielife.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private String code;
    private String message;
    private T data;
    private String requestId;

    public static <T> Result<T> ok(T data) {
        return new Result<>("OK", "ok", data, MDC.get(RequestIdFilter.REQUEST_ID));
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(String code, String message) {
        return new Result<>(code, message, null, MDC.get(RequestIdFilter.REQUEST_ID));
    }
}
