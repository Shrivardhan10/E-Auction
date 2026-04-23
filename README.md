# E-Auction Platform

A Spring Boot 3 based e-auction platform for collectibles and rare items with role-based workflows for Seller, Bidder, Admin, and Delivery Agent.

## Tech Stack

- Java 17
- Spring Boot (`web`, `thymeleaf`, `data-jpa`, `data-redis`, `websocket`)
- PostgreSQL (persistent data)
- Redis (real-time live bidding)
- Maven

## Core Features

- Multi-role authentication and dashboards (`SELLER`, `BIDDER`, `ADMIN`, `DELIVERY`)
- Item listing and admin approval/rejection flow
- Auction scheduling and automatic lifecycle transitions (`PENDING -> LIVE -> COMPLETED`)
- Real-time bidding with Redis + WebSocket broadcast
- Bid validation rules:
  - First bid must be at least base price
  - Next bids must be at least 10% above current highest (or configured min increment)
- Winner payment workflow:
  - `GUARANTEE` (50%) after auction completion
  - `FINAL` (remaining 50%) after delivery
- Fallback winner logic if guarantee payment expires
- Delivery verification with image-similarity check and settlement generation

## High-Level Workflow

1. Seller uploads item.
2. Premium items go through AI expert review; admin approves/rejects item.
3. Seller pushes approved item to auction.
4. Scheduler activates auction at start time and closes it at end time.
5. Bidders place live bids via Redis-backed flow.
6. On completion, winning bid is persisted and guarantee payment is created.
7. Delivery agent accepts task, verifies pickup image, and marks delivery.
8. Bidder completes final payment; settlement is created for seller.

## Project Structure

```text
src/main/java/com/eauction
  config/        # Redis, Web, WebSocket configuration
  controller/    # MVC and REST controllers by role/workflow
  dto/           # Request DTOs
  exception/     # Global handlers and domain exceptions
  model/entity/  # JPA entities
  repository/    # Spring Data repositories
  service/       # Core business logic and schedulers

src/main/resources/templates
  admin/
  bidder/
  delivery/
  seller/
```

## Database

- Schema and seed data: `schema.sql`
- Main domain entities include:
  - `users`, `items`, `auctions`, `bids`, `payments`, `delivery_verifications`, `settlements`

## Run Locally

### 1) Prerequisites

- Java 17+
- Maven (or use wrapper `mvnw`/`mvnw.cmd`)
- PostgreSQL database
- Redis instance (local, Docker, or cloud)

### 2) Configure profiles and connections

Set your environment-specific values in your application profile (for example `application-dev.yml`) for:

- PostgreSQL datasource URL/username/password
- Redis host/port/password (or URL)
- Any optional API keys used by AI review service

> The repository currently has `spring.profiles.active: dev` in `src/main/resources/application.yaml`.

### 3) Start the app

On Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

On macOS/Linux:

```bash
./mvnw spring-boot:run
```

Default app URL:

- `http://localhost:8080`

## Important Endpoints (Representative)

- Auth:
  - `GET /login`, `POST /login`, `GET /logout`, `GET|POST /register`
- Seller:
  - `GET /seller/dashboard`
  - `POST /seller/items/upload`
  - `POST /seller/items/{itemId}/push-to-auction`
- Bidder:
  - `GET /bidder/auctions`
  - `POST /api/auction/{auctionId}/bid`
  - `POST /bidder/payment/{auctionId}/pay`
- Admin:
  - `GET /admin/dashboard`
  - `POST /admin/items/{itemId}/approve`
  - `POST /admin/items/{itemId}/reject`
- Delivery:
  - `GET /delivery/dashboard`
  - `POST /delivery/accept/{auctionId}`
  - `POST /delivery/upload/{deliveryId}`
  - `POST /delivery/deliver/{deliveryId}`

## Diagrams

Generated diagrams are available in `docs/diagrams`:

- `state-diagram.png`
- `activity-diagram.png`

PlantUML source files are also included:

- `state-diagram.puml`
- `activity-diagram.puml`

## Notes

- Live bidding state is maintained in Redis for low-latency updates.
- Winning bid and final business records are persisted in PostgreSQL.
- Payment timeout handling can reassign winner to the next highest bidder.
