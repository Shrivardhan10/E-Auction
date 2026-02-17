# Redis-Powered Real-Time Auction Architecture

## E-Auction Platform — Implementation Guide

---

## 1. Problem Statement

The current E-Auction system stores all bid data directly in **PostgreSQL (NeonDB)**. When an auction goes LIVE, every bid placement requires:

1. A database read to fetch the current highest bid
2. Validation (10% increment rule)
3. A database write to save the new bid
4. Another write to update `auctions.current_highest_bid`

This works for low-traffic auctions but creates **bottlenecks** when many bidders compete simultaneously:

- **Latency**: Each bid round-trips to NeonDB (~50–150 ms per query over the network)
- **Contention**: The `synchronized` block serializes all bids, creating a queue
- **Stale data**: Bidders see outdated "current highest bid" because the page only refreshes on manual action
- **No real-time push**: Bidders must poll or refresh to see new bids

**Goal**: Use Redis as a **fast, in-memory cache layer** for live auction state, enabling sub-millisecond bid validation and real-time bid broadcasting.

---

## 2. Architecture Overview

```
┌─────────────┐       WebSocket        ┌───────────────────┐
│   Browser    │◄──────────────────────►│  Spring Boot App  │
│  (Bidder)    │   STOMP over WS        │                   │
└─────────────┘                         │  BidController    │
                                        │  AuctionWSHandler │
                                        │                   │
                                        │  ┌─────────────┐  │
                                        │  │ RedisBidSvc  │  │
                                        │  └──────┬──────┘  │
                                        └─────────┼─────────┘
                                                  │
                              ┌───────────────────┼───────────────────┐
                              │                   │                   │
                         ┌────▼────┐        ┌─────▼─────┐     ┌──────▼──────┐
                         │  Redis  │        │ PostgreSQL │     │  Redis Pub/ │
                         │ (Cache) │        │  (NeonDB)  │     │  Sub        │
                         │         │        │            │     │  (Broadcast)│
                         │ Sorted  │        │ bids table │     └─────────────┘
                         │ Sets +  │        │ auctions   │
                         │ Hashes  │        │ table      │
                         └─────────┘        └────────────┘
```

### Data Flow for a Bid

1. Bidder clicks "Place Bid" → WebSocket message sent
2. Server validates bid against **Redis** (not PostgreSQL)
3. If valid: write to Redis **Sorted Set** + publish to **Redis Pub/Sub** channel
4. All connected bidders receive the new bid in real-time via WebSocket
5. Background job **persists** bids to PostgreSQL asynchronously (write-behind)

---

## 3. Redis vs Docker vs Cloud — Which to Use?

### Option A: Redis Cloud (Recommended for your setup)

Since you're already using **NeonDB** (cloud PostgreSQL), the simplest approach is to use a cloud Redis provider:

| Provider | Free Tier | Latency | Setup |
|----------|-----------|---------|-------|
| **Redis Cloud** (redis.io) | 30 MB, 30 connections | ~5 ms (same region) | Sign up, get URL |
| **Upstash Redis** | 10K commands/day free | ~10 ms (serverless) | Sign up, get URL |
| **AWS ElastiCache** | None (paid) | ~1 ms (VPC) | AWS account needed |

**Recommendation**: Use **Redis Cloud** free tier or **Upstash** for development. Both give you a connection URL like:

```
redis://default:PASSWORD@redis-12345.c1.ap-southeast-1.ec2.redns.redis-cloud.com:12345
```

### Option B: Docker (Local Development)

```yaml
# docker-compose.yml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --maxmemory 100mb --maxmemory-policy allkeys-lru
```

```powershell
docker compose up -d
```

### Option C: Local Install (Windows)

Redis doesn't officially support Windows. Use WSL2 or Docker instead.

### Verdict

| Scenario | Best Choice |
|----------|-------------|
| Development/testing | Docker or Upstash free tier |
| Production (your NeonDB setup) | Redis Cloud (same AWS region: ap-southeast-1) |
| Team/college demo | Upstash (zero setup, free) |

---

## 4. Spring Boot Configuration

### 4.1 Dependencies (already in pom.xml)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- Add for WebSocket support -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

### 4.2 application-dev.yml

```yaml
spring:
  data:
    redis:
      host: redis-12345.c1.ap-southeast-1.ec2.redns.redis-cloud.com
      port: 12345
      password: YOUR_REDIS_PASSWORD
      ssl:
        enabled: true          # Required for Redis Cloud / Upstash
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 10
          max-idle: 5
          min-idle: 2
```

For **Upstash**, use URL mode:

```yaml
spring:
  data:
    redis:
      url: rediss://default:PASSWORD@gusc1-sharing-pika-30112.upstash.io:30112
```

### 4.3 Redis Configuration Class

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }
}
```

---

## 5. Redis Data Model

### 5.1 Keys Design

| Key Pattern | Type | Purpose | TTL |
|-------------|------|---------|-----|
| `auction:{auctionId}:state` | Hash | Auction metadata (status, startTime, endTime, basePrice) | Until auction ends + 1 hour |
| `auction:{auctionId}:bids` | Sorted Set | All bids, scored by amount | Until auction ends + 1 hour |
| `auction:{auctionId}:highest` | String | Current highest bid amount (atomic reads) | Until auction ends + 1 hour |
| `auction:{auctionId}:bidder:{bidderId}` | String | Bidder's latest bid (quick lookup) | Same as auction |

### 5.2 Example Data

```
# Auction state hash
HSET auction:abc-123:state
    status       "LIVE"
    itemId       "item-456"
    basePrice    "50000"
    startTime    "2025-01-15T10:00:00"
    endTime      "2025-01-15T12:00:00"
    highestBid   "72600"
    highestBidder "user-789"

# Bids sorted set (score = bid amount)
ZADD auction:abc-123:bids 50000 '{"bidderId":"u1","amount":50000,"ts":"..."}'
ZADD auction:abc-123:bids 55000 '{"bidderId":"u2","amount":55000,"ts":"..."}'
ZADD auction:abc-123:bids 60500 '{"bidderId":"u1","amount":60500,"ts":"..."}'
ZADD auction:abc-123:bids 66550 '{"bidderId":"u3","amount":66550,"ts":"..."}'
ZADD auction:abc-123:bids 72600 '{"bidderId":"u2","amount":72600,"ts":"..."}'

# Quick highest bid lookup
SET auction:abc-123:highest "72600"
```

---

## 6. Implementation — Core Services

### 6.1 RedisBidService (New)

This replaces the direct PostgreSQL queries during LIVE auctions:

```java
@Service
public class RedisBidService {

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    public RedisBidService(RedisTemplate<String, String> redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Load auction state from PostgreSQL into Redis when it goes LIVE.
     */
    public void activateAuction(Auction auction) {
        String key = "auction:" + auction.getAuctionId() + ":state";

        Map<String, String> state = Map.of(
            "status",       "LIVE",
            "itemId",       auction.getItemId().toString(),
            "basePrice",    auction.getCurrentHighestBid() != null
                            ? auction.getCurrentHighestBid().toPlainString()
                            : "0",
            "startTime",    auction.getStartTime().toString(),
            "endTime",      auction.getEndTime().toString(),
            "highestBid",   "0",
            "highestBidder", ""
        );

        redis.opsForHash().putAll(key, state);

        // Set TTL: auction duration + 1 hour buffer
        long ttlSeconds = java.time.Duration.between(
            LocalDateTime.now(), auction.getEndTime().plusHours(1)
        ).getSeconds();
        redis.expire(key, java.time.Duration.ofSeconds(Math.max(ttlSeconds, 60)));

        // Initialize highest bid key
        redis.opsForValue().set(
            "auction:" + auction.getAuctionId() + ":highest", "0"
        );
    }

    /**
     * Validate and place a bid atomically using Redis.
     * Returns the saved bid amount, or throws InvalidBidException.
     */
    public BidResult placeBid(UUID auctionId, UUID bidderId, BigDecimal amount) {
        String highestKey = "auction:" + auctionId + ":highest";
        String bidsKey = "auction:" + auctionId + ":bids";
        String stateKey = "auction:" + auctionId + ":state";

        // 1. Check auction is LIVE
        String status = (String) redis.opsForHash().get(stateKey, "status");
        if (!"LIVE".equals(status)) {
            throw new InvalidBidException("Auction is not active");
        }

        // 2. Atomic compare-and-set using Redis Lua script
        String luaScript = """
            local currentHighest = tonumber(redis.call('GET', KEYS[1]) or '0')
            local newBid = tonumber(ARGV[1])
            local minimumRequired = currentHighest * 1.10

            if currentHighest > 0 and newBid < minimumRequired then
                return '-1:' .. tostring(currentHighest) .. ':' .. tostring(minimumRequired)
            end

            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('ZADD', KEYS[2], newBid, ARGV[2])
            redis.call('HSET', KEYS[3], 'highestBid', ARGV[1])
            redis.call('HSET', KEYS[3], 'highestBidder', ARGV[3])
            return '1'
            """;

        String bidJson = String.format(
            "{\"bidderId\":\"%s\",\"amount\":%s,\"ts\":\"%s\"}",
            bidderId, amount.toPlainString(), LocalDateTime.now()
        );

        DefaultRedisScript<String> script = new DefaultRedisScript<>(luaScript, String.class);
        String result = redis.execute(script,
            List.of(highestKey, bidsKey, stateKey),
            amount.toPlainString(), bidJson, bidderId.toString()
        );

        if (result != null && result.startsWith("-1")) {
            String[] parts = result.split(":");
            throw new InvalidBidException(
                "Bid must be at least 10% higher than ₹" + parts[1] +
                ". Minimum: ₹" + parts[2]
            );
        }

        return new BidResult(auctionId, bidderId, amount, LocalDateTime.now());
    }

    /**
     * Get current highest bid from Redis (sub-millisecond).
     */
    public BigDecimal getCurrentHighestBid(UUID auctionId) {
        String val = redis.opsForValue().get("auction:" + auctionId + ":highest");
        return val != null ? new BigDecimal(val) : BigDecimal.ZERO;
    }

    /**
     * Get recent N bids for display.
     */
    public List<String> getRecentBids(UUID auctionId, int count) {
        String key = "auction:" + auctionId + ":bids";
        Set<String> bids = redis.opsForZSet().reverseRange(key, 0, count - 1);
        return bids != null ? new ArrayList<>(bids) : List.of();
    }

    public record BidResult(UUID auctionId, UUID bidderId, BigDecimal amount, LocalDateTime timestamp) {}
}
```

### 6.2 Why Lua Script?

The Lua script runs **atomically on the Redis server**. This means:

- No race condition: two bids arriving simultaneously are serialized by Redis
- No `synchronized` keyword needed in Java
- No distributed lock needed
- Single round-trip: read + validate + write happen in one command

This replaces the current `synchronized` + `@Transactional` approach with something that works across **multiple application instances** (horizontal scaling).

### 6.3 Write-Behind to PostgreSQL

Bids stored in Redis must eventually be persisted to PostgreSQL for durability:

```java
@Service
public class BidPersistenceService {

    private final BidRepository bidRepository;
    private final AuctionRepository auctionRepository;

    /**
     * Runs every 5 seconds — flushes recent Redis bids to PostgreSQL.
     */
    @Scheduled(fixedDelay = 5000)
    public void flushBidsToDatabase() {
        // For each active auction in Redis:
        // 1. Read all bids from the Sorted Set
        // 2. Compare with what's already in PostgreSQL
        // 3. Insert only new bids
        // 4. Update auction.currentHighestBid
    }

    /**
     * Called when auction ends — final sync.
     */
    @Transactional
    public void finalizeAuction(UUID auctionId) {
        // 1. Flush ALL remaining bids to PostgreSQL
        // 2. Determine winner from Redis highest
        // 3. Update auction status to COMPLETED
        // 4. Clean up Redis keys
    }
}
```

---

## 7. Real-Time Updates — WebSocket with STOMP

### 7.1 WebSocket Configuration

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");    // Clients subscribe here
        config.setApplicationDestinationPrefixes("/app");  // Clients send here
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-auction")
                .setAllowedOriginPatterns("*")
                .withSockJS();  // Fallback for older browsers
    }
}
```

### 7.2 Auction WebSocket Controller

```java
@Controller
public class AuctionWebSocketController {

    private final RedisBidService redisBidService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/bid")  // Client sends to /app/bid
    public void handleBid(@Payload PlaceBidRequest request, SimpMessageHeaderAccessor headerAccessor) {
        try {
            RedisBidService.BidResult result = redisBidService.placeBid(
                request.getAuctionId(),
                request.getBidderId(),
                request.getBidAmount()
            );

            // Broadcast to ALL subscribers of this auction
            messagingTemplate.convertAndSend(
                "/topic/auction/" + request.getAuctionId(),
                Map.of(
                    "type",     "NEW_BID",
                    "amount",   result.amount().toPlainString(),
                    "bidderId", result.bidderId().toString(),
                    "timestamp", result.timestamp().toString()
                )
            );
        } catch (InvalidBidException e) {
            // Send error only to the bidder who made the invalid bid
            headerAccessor.getSessionAttributes();
            messagingTemplate.convertAndSend(
                "/topic/auction/" + request.getAuctionId() + "/errors/" + request.getBidderId(),
                Map.of("type", "BID_ERROR", "message", e.getMessage())
            );
        }
    }
}
```

### 7.3 Client-Side JavaScript (Bidder Dashboard)

```javascript
// Connect to WebSocket
var socket = new SockJS('/ws-auction');
var stompClient = Stomp.over(socket);

stompClient.connect({}, function(frame) {
    // Subscribe to auction updates
    stompClient.subscribe('/topic/auction/' + auctionId, function(message) {
        var data = JSON.parse(message.body);

        if (data.type === 'NEW_BID') {
            // Update the UI instantly
            document.getElementById('currentBid').textContent = '₹' + data.amount;
            document.getElementById('bidCount').textContent =
                parseInt(document.getElementById('bidCount').textContent) + 1;

            // Add to bid history list
            addBidToHistory(data);

            // Update minimum bid hint
            var minNext = parseFloat(data.amount) * 1.10;
            document.getElementById('minBidHint').textContent =
                'Minimum bid: ₹' + minNext.toFixed(2);
        }
    });

    // Subscribe to personal errors
    stompClient.subscribe('/topic/auction/' + auctionId + '/errors/' + bidderId, function(msg) {
        var err = JSON.parse(msg.body);
        showError(err.message);
    });
});

// Place bid via WebSocket (instead of HTTP POST)
function placeBid(amount) {
    stompClient.send('/app/bid', {}, JSON.stringify({
        auctionId: auctionId,
        bidderId: bidderId,
        bidAmount: amount
    }));
}
```

---

## 8. Auction Lifecycle with Redis

### 8.1 State Machine

```
  PENDING ──────────► LIVE ──────────► COMPLETED
  (PostgreSQL)        (Redis)          (PostgreSQL)
                        │
                   Bids stored in      Winner determined
                   Redis Sorted Set    Bids flushed to DB
                   Real-time via WS    Settlement created
```

### 8.2 Activation (PENDING → LIVE)

A scheduled task checks for auctions whose start time has arrived:

```java
@Scheduled(fixedRate = 1000)  // Check every second
public void activatePendingAuctions() {
    List<Auction> pending = auctionRepository.findByStatus("PENDING");
    LocalDateTime now = LocalDateTime.now();

    for (Auction auction : pending) {
        if (!now.isBefore(auction.getStartTime())) {
            auction.setStatus("LIVE");
            auctionRepository.save(auction);

            // Load into Redis
            redisBidService.activateAuction(auction);

            // Notify all connected clients
            messagingTemplate.convertAndSend(
                "/topic/auctions/live",
                Map.of("type", "AUCTION_STARTED", "auctionId", auction.getAuctionId())
            );
        }
    }
}
```

### 8.3 Completion (LIVE → COMPLETED)

```java
@Scheduled(fixedRate = 1000)
public void completeLiveAuctions() {
    // Check Redis for auctions past endTime
    // For each:
    // 1. Get winner (highest bid from Redis)
    // 2. Flush all bids to PostgreSQL
    // 3. Update auction status to COMPLETED + set winnerId
    // 4. Create Settlement record
    // 5. Clean Redis keys
    // 6. Broadcast AUCTION_ENDED to WebSocket
}
```

---

## 9. Performance Comparison

| Metric | Current (PostgreSQL only) | With Redis |
|--------|--------------------------|------------|
| Bid validation latency | 50–150 ms (NeonDB round-trip) | <1 ms (Redis in-memory) |
| Concurrent bid handling | Sequential (`synchronized`) | Atomic Lua (parallel-safe) |
| Real-time updates | Manual refresh / polling | Instant via WebSocket |
| Horizontal scaling | NOT possible (synchronized) | YES (Redis is shared) |
| Bids per second | ~10–20 | ~10,000+ |
| Data durability | Immediate (every bid hits DB) | Write-behind (5s delay, acceptable for auctions) |

---

## 10. Step-by-Step Implementation Plan

### Phase 1: Redis Setup (30 minutes)

1. Sign up for **Upstash** or **Redis Cloud** free tier
2. Get the connection URL
3. Add to `application-dev.yml`
4. Create `RedisConfig.java`
5. Test connection: `redis.opsForValue().set("test", "hello")` in a `@PostConstruct`

### Phase 2: Redis Bid Service (2–3 hours)

1. Create `RedisBidService.java` with Lua script bid validation
2. Create `activateAuction()` method
3. Modify `AuctionService.refreshStatus()` to call `activateAuction()` when transitioning to LIVE
4. Update `BidService.placeBid()` to delegate to `RedisBidService` when auction is LIVE

### Phase 3: WebSocket Layer (2–3 hours)

1. Add `spring-boot-starter-websocket` dependency
2. Create `WebSocketConfig.java`
3. Create `AuctionWebSocketController.java`
4. Add SockJS + STOMP.js to bidder dashboard
5. Replace polling with WebSocket subscription

### Phase 4: Write-Behind Persistence (1–2 hours)

1. Create `BidPersistenceService.java` with `@Scheduled` flush
2. Implement `finalizeAuction()` for clean completion
3. Add error handling (what if Redis goes down mid-auction?)

### Phase 5: UI Enhancements (1–2 hours)

1. Live bid counter on auction cards
2. Real-time bid history feed
3. "Outbid!" notification when someone beats your bid
4. Countdown timer for auction end

---

## 11. Fallback Strategy

Redis should be treated as a **cache, not the source of truth**. If Redis goes down:

```java
public Bid placeBidSafe(PlaceBidRequest request) {
    try {
        // Try Redis-based fast path
        return redisBidService.placeBid(request);
    } catch (RedisConnectionException e) {
        log.warn("Redis unavailable, falling back to PostgreSQL");
        // Fall back to current synchronized + transactional approach
        return bidService.placeBid(request);
    }
}
```

---

## 12. Recommended Redis Cloud Setup (Upstash)

1. Go to [console.upstash.com](https://console.upstash.com)
2. Create a new Redis database
3. Select **Region**: `ap-southeast-1` (Singapore — same as your NeonDB)
4. Copy the connection details:

```yaml
# application-dev.yml
spring:
  data:
    redis:
      url: rediss://default:AXXXXXXXXXx@apn1-example.upstash.io:6379
  redis:
    repositories:
      enabled: false    # Keep this — you're using Redis for caching, not JPA repos
```

5. Test with a simple command in your app startup or a REST endpoint.

---

## 13. Summary

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Primary DB | PostgreSQL (NeonDB) | Persistent storage for users, items, auctions, bids |
| Cache / Real-time | Redis (Upstash / Redis Cloud) | Live bid state, atomic validation, Pub/Sub |
| Real-time delivery | WebSocket (STOMP + SockJS) | Push bid updates to all connected bidders |
| Bid validation | Redis Lua Script | Atomic 10% increment check without distributed locks |
| Persistence | Scheduled write-behind | Flush Redis bids to PostgreSQL every 5 seconds |
| Fallback | PostgreSQL direct | If Redis is unavailable, use current synchronized approach |

The key insight: **Redis handles the hot path** (live bidding) while **PostgreSQL remains the source of truth** (historical data, settlements, user management). This gives you the speed of in-memory operations with the durability of a relational database.
