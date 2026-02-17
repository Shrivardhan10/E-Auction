package com.eauction.service;

import com.eauction.dto.PlaceBidRequest;
import com.eauction.exception.InvalidBidException;
import com.eauction.model.entity.Bid;
import com.eauction.repository.BidRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import com.eauction.model.entity.Auction;
import com.eauction.repository.AuctionRepository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;

@Service
public class BidService {

    private final BidRepository bidRepository;
    private final AuctionRepository auctionRepository;

        public BidService(BidRepository bidRepository,
                        AuctionRepository auctionRepository) {
        this.bidRepository = bidRepository;
        this.auctionRepository = auctionRepository;
        }


    @Transactional
    public synchronized Bid placeBid(PlaceBidRequest request) {
        Auction auction = auctionRepository.findById(request.getAuctionId())
        .orElseThrow(() -> new InvalidBidException("Auction does not exist"));

LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        
if (now.isBefore(auction.getStartTime())) {
    throw new InvalidBidException("Auction has not started yet");
}

if (now.isAfter(auction.getEndTime())) {
    throw new InvalidBidException("Auction has ended");
}
if (!"LIVE".equalsIgnoreCase(auction.getStatus())) {
    throw new InvalidBidException("Auction is not active");
}

    BigDecimal currentHighest = bidRepository
            .findTopByAuctionIdOrderByAmountDesc(request.getAuctionId())
            .map(Bid::getAmount)
            .orElse(BigDecimal.ZERO);

    BigDecimal minimumAllowed = currentHighest.multiply(BigDecimal.valueOf(1.10));

    if (currentHighest.compareTo(BigDecimal.ZERO) > 0 &&
            request.getBidAmount().compareTo(minimumAllowed) < 0) {

        throw new InvalidBidException(
                "Bid must be at least 10% higher than current highest bid of ₹" +
                currentHighest.toPlainString() + ". Minimum bid: ₹" + minimumAllowed.toPlainString()
        );
    }

    // For first bid, it must be at least the base price (from item)
    if (currentHighest.compareTo(BigDecimal.ZERO) == 0 &&
            request.getBidAmount().compareTo(BigDecimal.ZERO) <= 0) {
        throw new InvalidBidException("Bid amount must be greater than zero");
    }

    Bid newBid = new Bid(
            request.getAuctionId(),
            request.getBidderId(),
            request.getBidAmount()
    );

    Bid savedBid = bidRepository.save(newBid);

    // Update the auction's current highest bid
    auction.setCurrentHighestBid(request.getBidAmount());
    auctionRepository.save(auction);

    return savedBid;
}
public Bid getHighestBid(UUID auctionId) {

    return bidRepository
            .findTopByAuctionIdOrderByAmountDesc(auctionId)
            .orElseThrow(() ->
                    new InvalidBidException("No bids found for this auction"));
}


public List<Bid> getBidHistory(UUID auctionId) {

    List<Bid> bids = bidRepository
            .findByAuctionIdOrderByCreatedAtDesc(auctionId);

    if (bids.isEmpty()) {
        throw new InvalidBidException("No bids found for this auction");
    }

    return bids;
}

}
