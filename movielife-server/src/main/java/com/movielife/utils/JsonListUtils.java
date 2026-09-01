package com.movielife.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movielife.exception.BusinessException;
import com.movielife.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JsonListUtils {

    private static final TypeReference<List<String>> STRING_LIST_TYPE =
            new TypeReference<List<String>>() { };

    private final ObjectMapper objectMapper;

    public String writeStrings(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Collections.emptyList() : values);
        } catch (JsonProcessingException exception) {
            log.error("Failed to serialize string list", exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    public List<String> readStrings(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            log.error("Invalid string-list JSON", exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
