package com.eauction.controller;

import com.eauction.model.entity.Item;
import com.eauction.service.ItemService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ItemImageController {

    private final ItemService itemService;

    public ItemImageController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping("/api/items/{itemId}/image")
    public ResponseEntity<byte[]> getItemImage(@PathVariable UUID itemId) {
        Item item = itemService.getItemById(itemId);
        if (item == null || item.getImageData() == null) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        String contentType = item.getImageContentType();
        if (contentType != null) {
            headers.setContentType(MediaType.parseMediaType(contentType));
        } else {
            headers.setContentType(MediaType.IMAGE_JPEG);
        }
        headers.setCacheControl("public, max-age=86400");

        return new ResponseEntity<>(item.getImageData(), headers, HttpStatus.OK);
    }
}
