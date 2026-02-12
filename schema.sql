-- ============================================================
-- E-AUCTION PLATFORM FOR COLLECTIBLES & RARE ITEMS
-- PostgreSQL Database Schema
-- ============================================================
--
-- Architecture:
--   5 Microservices — Seller, Bidder, Admin, Expert (LLM), Delivery
--   PostgreSQL     — all persistent data
--   Redis          — real-time bid queuing (not modelled here)
--
-- Business Rules:
--   NORMAL items  : base_price <= 10,000 INR, 0% brokerage
--   PREMIUM items : base_price >  10,000 INR, 5% brokerage, LLM-certified
--   Bid increment : >= min_increment_percent of current highest bid
--   Payment       : 50% guarantee after winning + 50% on delivery
--   Fallback      : if winner fails guarantee payment, next highest bidder wins
-- ============================================================


-- ============================================================
-- CLEANUP (allows safe re-execution on the same database)
-- ============================================================

DROP TABLE IF EXISTS settlements             CASCADE;
DROP TABLE IF EXISTS delivery_verifications   CASCADE;
DROP TABLE IF EXISTS payments                 CASCADE;
DROP TABLE IF EXISTS bids                     CASCADE;
DROP TABLE IF EXISTS auctions                CASCADE;
DROP TABLE IF EXISTS expert_certifications    CASCADE;
DROP TABLE IF EXISTS items                   CASCADE;
DROP TABLE IF EXISTS users                   CASCADE;

DROP TYPE IF EXISTS settlement_status;
DROP TYPE IF EXISTS delivery_status;
DROP TYPE IF EXISTS payment_status;
DROP TYPE IF EXISTS payment_type;
DROP TYPE IF EXISTS auction_status;
DROP TYPE IF EXISTS approval_status;
DROP TYPE IF EXISTS item_type;
DROP TYPE IF EXISTS user_role;

DROP FUNCTION IF EXISTS update_timestamp();


-- ============================================================
-- ENUM TYPES
-- ============================================================

CREATE TYPE user_role         AS ENUM ('SELLER', 'BIDDER', 'ADMIN', 'DELIVERY');
CREATE TYPE item_type         AS ENUM ('NORMAL', 'PREMIUM');
CREATE TYPE approval_status   AS ENUM ('PENDING', 'APPROVED', 'REJECTED');
CREATE TYPE auction_status    AS ENUM ('PENDING', 'LIVE', 'COMPLETED', 'CANCELLED');
CREATE TYPE payment_type      AS ENUM ('GUARANTEE', 'FINAL');
CREATE TYPE payment_status    AS ENUM ('PENDING', 'SUCCESS', 'FAILED');
CREATE TYPE delivery_status   AS ENUM ('PENDING_PICKUP', 'PICKED_UP', 'VERIFIED', 'REJECTED', 'DELIVERED');
CREATE TYPE settlement_status AS ENUM ('PENDING', 'PROCESSED', 'COMPLETED');


-- ============================================================
-- HELPER: auto-update updated_at on every row modification
-- ============================================================

CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- ============================================================
-- TABLE: users
-- All platform participants (sellers, bidders, admins, delivery)
-- ============================================================

CREATE TABLE users (
    user_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(100)  NOT NULL,
    email         VARCHAR(150)  UNIQUE NOT NULL,
    password_hash TEXT          NOT NULL,
    phone         VARCHAR(15)   NOT NULL,
    role          user_role     NOT NULL,
    address       TEXT,
    city          VARCHAR(100),
    state         VARCHAR(100),
    pincode       VARCHAR(10),
    is_active     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role  ON users(role);

CREATE TRIGGER trg_users_updated
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();


-- ============================================================
-- TABLE: items
-- Collectibles / rare items listed by sellers
-- admin_status tracks the admin approval workflow
-- ============================================================

CREATE TABLE items (
    item_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id     UUID            NOT NULL REFERENCES users(user_id),
    name          VARCHAR(200)    NOT NULL,
    description   TEXT            NOT NULL,
    image_url     TEXT            NOT NULL,
    item_type     item_type       NOT NULL,
    base_price    NUMERIC(12, 2)  NOT NULL CHECK (base_price > 0),
    admin_status  approval_status NOT NULL DEFAULT 'PENDING',
    admin_remarks TEXT,
    reviewed_by   UUID            REFERENCES users(user_id),
    reviewed_at   TIMESTAMP,
    created_at    TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_price_by_type CHECK (
        (item_type = 'NORMAL'  AND base_price <= 10000.00)
        OR
        (item_type = 'PREMIUM' AND base_price >  10000.00)
    )
);

CREATE INDEX idx_items_seller       ON items(seller_id);
CREATE INDEX idx_items_type         ON items(item_type);
CREATE INDEX idx_items_admin_status ON items(admin_status);

CREATE TRIGGER trg_items_updated
    BEFORE UPDATE ON items
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();


-- ============================================================
-- TABLE: expert_certifications
-- LLM-based authentication & grading for PREMIUM items only
-- ============================================================

CREATE TABLE expert_certifications (
    cert_id      UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id      UUID           UNIQUE NOT NULL REFERENCES items(item_id),
    llm_model    VARCHAR(100)   NOT NULL,
    llm_score    NUMERIC(5, 2)  NOT NULL CHECK (llm_score BETWEEN 0 AND 100),
    grade        VARCHAR(5)     NOT NULL,
    remarks      TEXT,
    is_certified BOOLEAN        NOT NULL DEFAULT FALSE,
    certified_at TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cert_item ON expert_certifications(item_id);


-- ============================================================
-- TABLE: auctions
-- One auction per approved item
-- Seller sets start_time, end_time, and min_increment_percent
-- ============================================================

CREATE TABLE auctions (
    auction_id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id               UUID           UNIQUE NOT NULL REFERENCES items(item_id),
    start_time            TIMESTAMP      NOT NULL,
    end_time              TIMESTAMP      NOT NULL,
    status                auction_status NOT NULL DEFAULT 'PENDING',
    min_increment_percent NUMERIC(5, 2)  NOT NULL DEFAULT 10.00
                                         CHECK (min_increment_percent > 0),
    current_highest_bid   NUMERIC(12, 2),
    winner_id             UUID           REFERENCES users(user_id),
    created_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_auction_window CHECK (end_time > start_time)
);

CREATE INDEX idx_auctions_status ON auctions(status);
CREATE INDEX idx_auctions_item   ON auctions(item_id);
CREATE INDEX idx_auctions_winner ON auctions(winner_id);
CREATE INDEX idx_auctions_time   ON auctions(start_time, end_time);

CREATE TRIGGER trg_auctions_updated
    BEFORE UPDATE ON auctions
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();


-- ============================================================
-- TABLE: bids
-- Individual bids on live auctions
-- Bid queuing & increment validation handled by Redis layer;
-- validated records are persisted here.
-- ============================================================

CREATE TABLE bids (
    bid_id     UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id UUID           NOT NULL REFERENCES auctions(auction_id),
    bidder_id  UUID           NOT NULL REFERENCES users(user_id),
    amount     NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bids_auction        ON bids(auction_id);
CREATE INDEX idx_bids_bidder         ON bids(bidder_id);
CREATE INDEX idx_bids_auction_amount ON bids(auction_id, amount DESC);


-- ============================================================
-- TABLE: payments
-- Two-phase payment per auction:
--   GUARANTEE — 50% of winning bid, paid immediately after win
--   FINAL     — remaining 50%, paid on delivery
-- If GUARANTEE fails -> auction winner falls back to next bidder
-- ============================================================

CREATE TABLE payments (
    payment_id   UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id   UUID           NOT NULL REFERENCES auctions(auction_id),
    bidder_id    UUID           NOT NULL REFERENCES users(user_id),
    amount       NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    payment_type payment_type   NOT NULL,
    status       payment_status NOT NULL DEFAULT 'PENDING',
    due_by       TIMESTAMP,
    paid_at      TIMESTAMP,
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_auction ON payments(auction_id);
CREATE INDEX idx_payments_bidder  ON payments(bidder_id);
CREATE INDEX idx_payments_status  ON payments(status);


-- ============================================================
-- TABLE: delivery_verifications
-- Delivery agent picks up item from seller, photographs it.
-- Image similarity compared against seller's original listing photo.
-- If similarity >= threshold -> item verified -> delivered to buyer.
-- ============================================================

CREATE TABLE delivery_verifications (
    delivery_id      UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id       UUID            UNIQUE NOT NULL REFERENCES auctions(auction_id),
    agent_id         UUID            NOT NULL REFERENCES users(user_id),
    pickup_image_url TEXT,
    similarity_score NUMERIC(5, 2)   CHECK (similarity_score BETWEEN 0 AND 100),
    is_verified      BOOLEAN,
    status           delivery_status NOT NULL DEFAULT 'PENDING_PICKUP',
    picked_up_at     TIMESTAMP,
    delivered_at     TIMESTAMP,
    created_at       TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_delivery_auction ON delivery_verifications(auction_id);
CREATE INDEX idx_delivery_agent   ON delivery_verifications(agent_id);
CREATE INDEX idx_delivery_status  ON delivery_verifications(status);

CREATE TRIGGER trg_delivery_updated
    BEFORE UPDATE ON delivery_verifications
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();


-- ============================================================
-- TABLE: settlements
-- Final payout to seller after successful delivery
-- PREMIUM items: 5% brokerage deducted by platform
-- NORMAL  items: 0% brokerage
-- ============================================================

CREATE TABLE settlements (
    settlement_id     UUID              PRIMARY KEY DEFAULT gen_random_uuid(),
    auction_id        UUID              UNIQUE NOT NULL REFERENCES auctions(auction_id),
    seller_id         UUID              NOT NULL REFERENCES users(user_id),
    sale_amount       NUMERIC(12, 2)    NOT NULL CHECK (sale_amount > 0),
    brokerage_percent NUMERIC(5, 2)     NOT NULL DEFAULT 0.00,
    brokerage_amount  NUMERIC(12, 2)    NOT NULL DEFAULT 0.00,
    seller_amount     NUMERIC(12, 2)    NOT NULL CHECK (seller_amount > 0),
    status            settlement_status NOT NULL DEFAULT 'PENDING',
    settled_at        TIMESTAMP,
    created_at        TIMESTAMP         NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_settlements_auction ON settlements(auction_id);
CREATE INDEX idx_settlements_seller  ON settlements(seller_id);
CREATE INDEX idx_settlements_status  ON settlements(status);


-- ============================================================
-- ==================  REALISTIC DUMMY DATA  ==================
-- ============================================================
-- Password for every dummy user: Test@1234
-- The bcrypt hash below corresponds to that password.
--
-- Scenario coverage:
--   • Completed premium auction  (full workflow: certified -> approved
--     -> auctioned -> bids -> payment -> delivery -> settlement)
--   • Live premium auction       (certified, approved, bids in progress)
--   • Live normal auction        (approved, bids in progress)
--   • Pending normal auction     (approved, scheduled for future)
--   • Pending-approval item      (uploaded, not yet reviewed by admin)
--   • Rejected item              (admin found it irrelevant)
-- ============================================================


-- ----------------------------------------------------------
-- USERS  (2 sellers, 3 bidders, 1 admin, 1 delivery agent)
-- ----------------------------------------------------------

INSERT INTO users (user_id, name, email, password_hash, phone, role, address, city, state, pincode)
VALUES
('f47ac10b-58cc-4372-a567-0e02b2c3d479',
 'Vikram Mehta', 'vikram.mehta@gmail.com',
 '$2b$12$WApznUPhDubNIh3nv1gJHOT3MoK5L3PnDcma13QfzO6Kz0GyXBnfe',
 '9876543210', 'SELLER',
 '34, Chor Bazaar, Mutton Street', 'Mumbai', 'Maharashtra', '400003'),

('d290f1ee-6c54-4b01-90e6-d701748f0851',
 'Priya Sharma', 'priya.sharma@gmail.com',
 '$2b$12$WApznUPhDubNIh3nv1gJHOT3MoK5L3PnDcma13QfzO6Kz0GyXBnfe',
 '9876543211', 'SELLER',
 '12, Sundar Nagar Market', 'New Delhi', 'Delhi', '110003'),

('7c9e6679-7425-40de-944b-e07fc1f90ae7',
 'Arjun Patel', 'arjun.patel@gmail.com',
 '$2b$12$WApznUPhDubNIh3nv1gJHOT3MoK5L3PnDcma13QfzO6Kz0GyXBnfe',
 '9876543212', 'BIDDER',
 '8, CG Road, Navrangpura', 'Ahmedabad', 'Gujarat', '380009'),

('550e8400-e29b-41d4-a716-446655440000',
 'Sneha Reddy', 'sneha.reddy@gmail.com',
 '$2b$12$WApznUPhDubNIh3nv1gJHOT3MoK5L3PnDcma13QfzO6Kz0GyXBnfe',
 '9876543213', 'BIDDER',
 '45, Jubilee Hills, Road No. 10', 'Hyderabad', 'Telangana', '500033'),

('6ba7b810-9dad-11d1-80b4-00c04fd430c8',
 'Karan Singh', 'karan.singh@gmail.com',
 '$2b$12$WApznUPhDubNIh3nv1gJHOT3MoK5L3PnDcma13QfzO6Kz0GyXBnfe',
 '9876543214', 'BIDDER',
 '7, MI Road', 'Jaipur', 'Rajasthan', '302001'),

('9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d',
 'Neha Gupta', 'neha.gupta@eauction.in',
 '$2b$12$WApznUPhDubNIh3nv1gJHOT3MoK5L3PnDcma13QfzO6Kz0GyXBnfe',
 '9876543215', 'ADMIN',
 'E-Auction HQ, Koramangala 4th Block', 'Bangalore', 'Karnataka', '560034'),

('1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed',
 'Raj Kumar', 'raj.kumar@eauction.in',
 '$2b$12$WApznUPhDubNIh3nv1gJHOT3MoK5L3PnDcma13QfzO6Kz0GyXBnfe',
 '9876543216', 'DELIVERY',
 '22, Andheri East, MIDC', 'Mumbai', 'Maharashtra', '400093');


-- ----------------------------------------------------------
-- ITEMS  (6 items covering every admin_status and item_type)
-- ----------------------------------------------------------

INSERT INTO items (item_id, seller_id, name, description, image_url, item_type, base_price,
                   admin_status, admin_remarks, reviewed_by, reviewed_at)
VALUES
-- 1. NORMAL / APPROVED — live auction running
('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
 '1862 Queen Victoria Silver Rupee',
 'Rare British India silver one-rupee coin minted in 1862 at the Calcutta Mint. Crowned bust of Queen Victoria on obverse. Good VF condition with original patina.',
 'https://storage.eauction.in/items/1862_victoria_silver_rupee.jpg',
 'NORMAL', 8500.00,
 'APPROVED', 'Genuine numismatic collectible. Approved.',
 '9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d', NOW() - INTERVAL '10 days'),

-- 2. PREMIUM / APPROVED — live auction running, LLM certified
('b1eebc99-9c0b-4ef8-bb6d-6bb9bd380a22',
 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
 'Mughal Miniature Painting — Darbar Scene',
 'Original 18th-century Mughal miniature watercolour on handmade wasli paper depicting Emperor Shah Alam II holding court. Approx 25x18 cm with gold-leaf detailing.',
 'https://storage.eauction.in/items/mughal_darbar_painting.jpg',
 'PREMIUM', 75000.00,
 'APPROVED', 'Authentic Mughal-era artwork. Fits collectible domain.',
 '9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d', NOW() - INTERVAL '8 days'),

-- 3. PREMIUM / APPROVED — completed auction, full workflow done
('c2eebc99-9c0b-4ef8-bb6d-6bb9bd380a33',
 'd290f1ee-6c54-4b01-90e6-d701748f0851',
 'Sachin Tendulkar Signed Cricket Bat — 2011 World Cup',
 'SG cricket bat personally signed by Sachin Tendulkar after the 2011 ICC World Cup final at Wankhede Stadium. Includes BCCI certificate of authenticity.',
 'https://storage.eauction.in/items/sachin_signed_bat_2011.jpg',
 'PREMIUM', 150000.00,
 'APPROVED', 'Iconic sports memorabilia with verified provenance.',
 '9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d', NOW() - INTERVAL '20 days'),

-- 4. NORMAL / APPROVED — auction scheduled for future (PENDING start)
('d3eebc99-9c0b-4ef8-bb6d-6bb9bd380a44',
 'd290f1ee-6c54-4b01-90e6-d701748f0851',
 '1947 Independence Commemorative Stamp Set',
 'Complete set of four India Post stamps released on 15 Aug 1947 celebrating independence. Mint condition, never hinged, stored in acid-free sleeve.',
 'https://storage.eauction.in/items/1947_independence_stamps.jpg',
 'NORMAL', 4500.00,
 'APPROVED', 'Philatelic collectible. Approved.',
 '9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d', NOW() - INTERVAL '2 days'),

-- 5. PREMIUM / PENDING — awaiting admin review, no auction yet
('e4eebc99-9c0b-4ef8-bb6d-6bb9bd380a55',
 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
 'Raja Ravi Varma Oleograph — Shakuntala, 1890s',
 'Original chromolithograph print by the Ravi Varma Press, Lonavala, circa 1895. Depicts Shakuntala looking back for King Dushyanta. Some foxing on edges.',
 'https://storage.eauction.in/items/ravi_varma_shakuntala.jpg',
 'PREMIUM', 250000.00,
 'PENDING', NULL, NULL, NULL),

-- 6. NORMAL / REJECTED — admin found it irrelevant
('f5eebc99-9c0b-4ef8-bb6d-6bb9bd380a66',
 'd290f1ee-6c54-4b01-90e6-d701748f0851',
 'Vintage Brass Telescope',
 'Decorative brass telescope, approx 40 cm length. Modern reproduction styled to look antique. No markings or provenance.',
 'https://storage.eauction.in/items/brass_telescope.jpg',
 'NORMAL', 6000.00,
 'REJECTED', 'Modern reproduction, not a genuine collectible or rare item.',
 '9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d', NOW() - INTERVAL '3 days');


-- ----------------------------------------------------------
-- EXPERT CERTIFICATIONS  (only for PREMIUM approved items)
-- ----------------------------------------------------------

INSERT INTO expert_certifications (cert_id, item_id, llm_model, llm_score, grade, remarks, is_certified, certified_at)
VALUES
('40a1bc99-1111-4ef8-bb6d-6bb9bd380a01',
 'b1eebc99-9c0b-4ef8-bb6d-6bb9bd380a22',
 'gpt-4o', 94.50, 'A',
 'Watercolour pigments and paper age consistent with 18th-century Mughal workshops. Gold-leaf technique matches known specimens. High confidence of authenticity.',
 TRUE, NOW() - INTERVAL '9 days'),

('40a1bc99-2222-4ef8-bb6d-6bb9bd380a02',
 'c2eebc99-9c0b-4ef8-bb6d-6bb9bd380a33',
 'gpt-4o', 91.20, 'A',
 'Signature ink analysis consistent with 2010-2012 period. Bat model matches SG equipment used during 2011 World Cup. BCCI certificate reference verified.',
 TRUE, NOW() - INTERVAL '21 days');


-- ----------------------------------------------------------
-- AUCTIONS
-- ----------------------------------------------------------

INSERT INTO auctions (auction_id, item_id, start_time, end_time, status,
                      min_increment_percent, current_highest_bid, winner_id)
VALUES
-- Silver Rupee — LIVE
('10a1bc99-0001-4ef8-bb6d-6bb9bd380a01',
 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
 NOW() - INTERVAL '1 day',  NOW() + INTERVAL '4 days',
 'LIVE', 10.00, 10500.00, NULL),

-- Mughal Painting — LIVE
('10a1bc99-0002-4ef8-bb6d-6bb9bd380a02',
 'b1eebc99-9c0b-4ef8-bb6d-6bb9bd380a22',
 NOW() - INTERVAL '12 hours',  NOW() + INTERVAL '5 days',
 'LIVE', 10.00, 83000.00, NULL),

-- Signed Bat — COMPLETED (winner: Sneha Reddy)
('10a1bc99-0003-4ef8-bb6d-6bb9bd380a03',
 'c2eebc99-9c0b-4ef8-bb6d-6bb9bd380a33',
 NOW() - INTERVAL '18 days',  NOW() - INTERVAL '11 days',
 'COMPLETED', 10.00, 182000.00,
 '550e8400-e29b-41d4-a716-446655440000'),

-- Stamp Collection — PENDING (future)
('10a1bc99-0004-4ef8-bb6d-6bb9bd380a04',
 'd3eebc99-9c0b-4ef8-bb6d-6bb9bd380a44',
 NOW() + INTERVAL '2 days',  NOW() + INTERVAL '7 days',
 'PENDING', 10.00, NULL, NULL);


-- ----------------------------------------------------------
-- BIDS  (all amounts respect the >= 10% increment rule)
-- ----------------------------------------------------------

INSERT INTO bids (bid_id, auction_id, bidder_id, amount, created_at)
VALUES
-- Silver Rupee auction  (base = 8500, LIVE)
('20b1bc99-0001-4ef8-bb6d-6bb9bd380a01',
 '10a1bc99-0001-4ef8-bb6d-6bb9bd380a01',
 '7c9e6679-7425-40de-944b-e07fc1f90ae7',       -- Arjun
 8500.00,  NOW() - INTERVAL '20 hours'),         -- first bid = base price

('20b1bc99-0002-4ef8-bb6d-6bb9bd380a02',
 '10a1bc99-0001-4ef8-bb6d-6bb9bd380a01',
 '550e8400-e29b-41d4-a716-446655440000',        -- Sneha
 9500.00,  NOW() - INTERVAL '16 hours'),         -- +11.8% over 8500

('20b1bc99-0003-4ef8-bb6d-6bb9bd380a03',
 '10a1bc99-0001-4ef8-bb6d-6bb9bd380a01',
 '6ba7b810-9dad-11d1-80b4-00c04fd430c8',        -- Karan
 10500.00, NOW() - INTERVAL '8 hours'),          -- +10.5% over 9500

-- Mughal Painting auction  (base = 75000, LIVE)
('20b1bc99-0004-4ef8-bb6d-6bb9bd380a04',
 '10a1bc99-0002-4ef8-bb6d-6bb9bd380a02',
 '550e8400-e29b-41d4-a716-446655440000',        -- Sneha
 75000.00, NOW() - INTERVAL '10 hours'),         -- first bid = base price

('20b1bc99-0005-4ef8-bb6d-6bb9bd380a05',
 '10a1bc99-0002-4ef8-bb6d-6bb9bd380a02',
 '7c9e6679-7425-40de-944b-e07fc1f90ae7',        -- Arjun
 83000.00, NOW() - INTERVAL '6 hours'),          -- +10.7% over 75000

-- Signed Bat auction  (base = 150000, COMPLETED)
('20b1bc99-0006-4ef8-bb6d-6bb9bd380a06',
 '10a1bc99-0003-4ef8-bb6d-6bb9bd380a03',
 '6ba7b810-9dad-11d1-80b4-00c04fd430c8',        -- Karan
 150000.00, NOW() - INTERVAL '17 days'),         -- first bid = base price

('20b1bc99-0007-4ef8-bb6d-6bb9bd380a07',
 '10a1bc99-0003-4ef8-bb6d-6bb9bd380a03',
 '7c9e6679-7425-40de-944b-e07fc1f90ae7',        -- Arjun
 165000.00, NOW() - INTERVAL '15 days'),         -- +10.0% over 150000

('20b1bc99-0008-4ef8-bb6d-6bb9bd380a08',
 '10a1bc99-0003-4ef8-bb6d-6bb9bd380a03',
 '550e8400-e29b-41d4-a716-446655440000',        -- Sneha  (winner)
 182000.00, NOW() - INTERVAL '13 days');         -- +10.3% over 165000


-- ----------------------------------------------------------
-- PAYMENTS  (completed auction — Signed Bat)
-- Winning bid = 182000 -> 50% guarantee + 50% final
-- ----------------------------------------------------------

INSERT INTO payments (payment_id, auction_id, bidder_id, amount,
                      payment_type, status, due_by, paid_at)
VALUES
('30c1bc99-0001-4ef8-bb6d-6bb9bd380a01',
 '10a1bc99-0003-4ef8-bb6d-6bb9bd380a03',
 '550e8400-e29b-41d4-a716-446655440000',        -- Sneha
 91000.00, 'GUARANTEE', 'SUCCESS',
 NOW() - INTERVAL '9 days',                      -- 48h deadline after auction end
 NOW() - INTERVAL '10 days'),                    -- paid before deadline

('30c1bc99-0002-4ef8-bb6d-6bb9bd380a02',
 '10a1bc99-0003-4ef8-bb6d-6bb9bd380a03',
 '550e8400-e29b-41d4-a716-446655440000',        -- Sneha
 91000.00, 'FINAL', 'SUCCESS',
 NOW() - INTERVAL '5 days',
 NOW() - INTERVAL '6 days');


-- ----------------------------------------------------------
-- DELIVERY VERIFICATION  (completed auction — Signed Bat)
-- ----------------------------------------------------------

INSERT INTO delivery_verifications (delivery_id, auction_id, agent_id,
                                    pickup_image_url, similarity_score,
                                    is_verified, status, picked_up_at, delivered_at)
VALUES
('50d1bc99-0001-4ef8-bb6d-6bb9bd380a01',
 '10a1bc99-0003-4ef8-bb6d-6bb9bd380a03',
 '1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed',        -- Raj Kumar
 'https://storage.eauction.in/delivery/pickup_sachin_bat_20260120.jpg',
 96.80, TRUE, 'DELIVERED',
 NOW() - INTERVAL '7 days',                      -- picked up from Priya
 NOW() - INTERVAL '6 days');                     -- delivered to Sneha


-- ----------------------------------------------------------
-- SETTLEMENT  (completed premium auction — Signed Bat)
-- Sale: 182000 | 5% brokerage: 9100 | Seller receives: 172900
-- ----------------------------------------------------------

INSERT INTO settlements (settlement_id, auction_id, seller_id, sale_amount,
                         brokerage_percent, brokerage_amount, seller_amount,
                         status, settled_at)
VALUES
('60e1bc99-0001-4ef8-bb6d-6bb9bd380a01',
 '10a1bc99-0003-4ef8-bb6d-6bb9bd380a03',
 'd290f1ee-6c54-4b01-90e6-d701748f0851',        -- Priya Sharma (seller)
 182000.00, 5.00, 9100.00, 172900.00,
 'COMPLETED', NOW() - INTERVAL '5 days');
