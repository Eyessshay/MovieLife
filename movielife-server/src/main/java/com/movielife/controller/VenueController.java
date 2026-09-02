package com.movielife.controller;

import com.movielife.common.Result;
import com.movielife.dto.VenueQueryDTO;
import com.movielife.dto.VenueNearbyQueryDTO;
import com.movielife.service.VenueService;
import com.movielife.vo.HallVO;
import com.movielife.vo.PageVO;
import com.movielife.vo.VenueDetailVO;
import com.movielife.vo.VenueListItemVO;
import com.movielife.vo.VenueNearbyVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    @GetMapping
    public Result<PageVO<VenueListItemVO>> list(@Valid VenueQueryDTO queryDTO) {
        return Result.ok(venueService.queryVenues(queryDTO));
    }

    @GetMapping("/nearby")
    public Result<List<VenueNearbyVO>> nearby(@Valid VenueNearbyQueryDTO queryDTO) {
        return Result.ok(venueService.queryNearbyVenues(queryDTO));
    }

    @GetMapping("/{id}")
    public Result<VenueDetailVO> detail(@PathVariable Long id) {
        return Result.ok(venueService.queryVenueDetail(id));
    }

    @GetMapping("/{id}/halls")
    public Result<List<HallVO>> halls(@PathVariable Long id) {
        return Result.ok(venueService.queryVenueHalls(id));
    }
}
