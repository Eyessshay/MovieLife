package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.VenueQueryDTO;
import com.movielife.service.VenueService;
import com.movielife.vo.PageVO;
import com.movielife.vo.VenueListItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    @GetMapping
    public Result<PageVO<VenueListItemVO>> list(@Valid VenueQueryDTO queryDTO) {
        return Result.ok(venueService.queryVenues(queryDTO));
    }
}
