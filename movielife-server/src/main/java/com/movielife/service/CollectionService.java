package com.movielife.service;

import com.movielife.dto.CollectionQueryDTO;
import com.movielife.vo.CollectionCheckVO;
import com.movielife.vo.CollectionVO;
import com.movielife.vo.PageVO;

public interface CollectionService {
    void collect(String targetType, Long targetId);
    void uncollect(String targetType, Long targetId);
    PageVO<CollectionVO> queryCollections(CollectionQueryDTO query);
    CollectionCheckVO check(String targetType, Long targetId);
}
