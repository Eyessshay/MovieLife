package com.movielife.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class CollectionVO {
    private String targetType;
    private Long targetId;
    private Object target;
    private LocalDateTime collectTime;
}
