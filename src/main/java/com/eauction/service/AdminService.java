package com.eauction.service;

import com.eauction.model.entity.Item;
import com.eauction.model.entity.User;
import com.eauction.repository.ItemRepository;
import com.eauction.repository.UserRepository;
import com.eauction.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final SettlementRepository settlementRepository;

    public List<Item> getPendingItems() {
        return itemRepository.findByAdminStatus("PENDING");
    }

    public List<Item> getAllItems() {
        return itemRepository.findAll();
    }

    public void approveItem(UUID itemId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Item not found"));

        if (!"PENDING".equals(item.getAdminStatus())) {
            throw new RuntimeException("Already reviewed");
        }

        item.setAdminStatus("APPROVED");
        item.setReviewedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    public void rejectItem(UUID itemId, String remarks) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Item not found"));

        item.setAdminStatus("REJECTED");
        item.setAdminRemarks(remarks);
        item.setReviewedAt(LocalDateTime.now());
        itemRepository.save(item);
    }

    public void deactivateUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setIsActive(false);
        userRepository.save(user);
    }

    public void activateUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setIsActive(true);
        userRepository.save(user);
    }

    public void toggleUserStatus(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        user.setIsActive(!Boolean.TRUE.equals(user.getIsActive()));
        userRepository.save(user);
    }

    public List<User> getNonAdminUsers() {
        return userRepository.findAll().stream()
                .filter(u -> !"ADMIN".equals(u.getRole()))
                .toList();
    }

    public BigDecimal getTotalBrokerage() {
        return settlementRepository.sumBrokerage();
    }

    public long getTotalUserCount() {
        return userRepository.count();
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }
}
