package com.eauction.service;

import com.eauction.dto.PlaceBidRequest;
import com.eauction.exception.InvalidBidException;
import com.eauction.model.entity.Bid;
import com.eauction.repository.BidRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BidService {

    private final BidRepository bidRepository;

    public BidService(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
    }

    public Bid placeBid(PlaceBidRequest request) {

    BigDecimal currentHighest = bidRepository
            .findTopByAuctionIdOrderByAmountDesc(request.getAuctionId())
            .map(Bid::getAmount)
            .orElse(BigDecimal.ZERO);

    BigDecimal minimumAllowed = currentHighest.multiply(BigDecimal.valueOf(1.10));

    if (currentHighest.compareTo(BigDecimal.ZERO) > 0 &&
            request.getBidAmount().compareTo(minimumAllowed) < 0) {

        throw new InvalidBidException(
                "Bid must be at least 10% higher than current highest bid"
        );
    }

    Bid newBid = new Bid(
            request.getAuctionId(),
            request.getBidderId(),
            request.getBidAmount()
    );

    return bidRepository.save(newBid);
}

}
