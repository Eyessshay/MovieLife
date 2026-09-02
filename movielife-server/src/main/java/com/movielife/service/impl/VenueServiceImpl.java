package com.movielife.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.movielife.dto.VenueQueryDTO;
import com.movielife.entity.Venue;
import com.movielife.enums.VenueType;
import com.movielife.mapper.VenueMapper;
import com.movielife.service.VenueService;
import com.movielife.vo.PageVO;
import com.movielife.vo.VenueListItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VenueServiceImpl implements VenueService {

    private static final int ENABLED_STATUS = 1;

    private final VenueMapper venueMapper;

    @Override
    public PageVO<VenueListItemVO> queryVenues(VenueQueryDTO queryDTO) {
        LambdaQueryWrapper<Venue> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Venue::getStatus, ENABLED_STATUS)
                .eq(StrUtil.isNotBlank(queryDTO.getCity()), Venue::getCity, queryDTO.getCity())
                .eq(queryDTO.getType() != null, Venue::getType,
                        queryDTO.getType() == null ? null : queryDTO.getType().getDbValue())
                .like(StrUtil.isNotBlank(queryDTO.getKeyword()), Venue::getName, queryDTO.getKeyword())
                .orderByDesc(Venue::getId);

        Page<Venue> venuePage = venueMapper.selectPage(
                new Page<>(queryDTO.getPage(), queryDTO.getPageSize()),
                wrapper
        );
        List<VenueListItemVO> list = venuePage.getRecords().stream()
                .map(this::toListVO)
                .collect(Collectors.toList());

        return new PageVO<>(list, venuePage.getTotal(), queryDTO.getPage(), queryDTO.getPageSize());
    }

    private VenueListItemVO toListVO(Venue venue) {
        VenueListItemVO vo = new VenueListItemVO();
        vo.setId(venue.getId());
        vo.setName(venue.getName());
        vo.setType(VenueType.fromDbValue(venue.getType()));
        vo.setCity(venue.getCity());
        vo.setAddress(venue.getAddress());
        vo.setAvgScore(venue.getAvgScore());
        vo.setOpenTime(venue.getOpenTime());
        return vo;
    }
}
