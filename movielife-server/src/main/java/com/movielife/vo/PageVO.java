package com.movielife.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PageVO<T> {

    private List<T> list;
    private Long total;
    private Integer page;
    private Integer pageSize;
}
