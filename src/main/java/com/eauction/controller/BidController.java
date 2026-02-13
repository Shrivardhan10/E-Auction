package com.eauction.controller;

import com.eauction.dto.PlaceBidRequest;
import com.eauction.model.entity.Bid;
import com.eauction.service.BidService;

import java.util.UUID;
import java.util.List;
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
    @GetMapping("/highest/{auctionId}")
    public Bid getHighestBid(@PathVariable UUID auctionId) {
        return bidService.getHighestBid(auctionId);
}
@GetMapping("/auction/{auctionId}")
public List<Bid> getBidHistory(@PathVariable UUID auctionId) {
    return bidService.getBidHistory(auctionId);
}

}
