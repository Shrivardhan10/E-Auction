package com.eauction.controller;

import com.eauction.dto.PlaceBidRequest;
import com.eauction.model.entity.Bid;
import com.eauction.service.BidService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bids")
public class BidController {

    private final BidService bidService;

    public BidController(BidService bidService) {
        this.bidService = bidService;
    }

    @PostMapping("/place")
    public Bid placeBid(@RequestBody PlaceBidRequest request) {
        return bidService.placeBid(request);
    }
}
