package com.movielife.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ScrollVO<T> {
    private List<T> list;
    private Long minTime;
    private Integer offset;
}
