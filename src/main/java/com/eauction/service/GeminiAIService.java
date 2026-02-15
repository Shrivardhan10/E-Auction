package com.eauction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Calls Google Gemini API (multimodal) to evaluate premium item consistency.
 * Includes retry with exponential backoff for rate-limit (429) errors.
 */
@Service
public class GeminiAIService {

    private static final Logger log = LoggerFactory.getLogger(GeminiAIService.class);
    private static final String MODEL = "gemini-2.5-flash-lite";
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 5_000; // 5 seconds

    @Value("${gemini.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiAIService() {
        this.restTemplate = new RestTemplate();
        // Set connection and read timeouts so calls don't hang forever
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);  // 15s connect
        factory.setReadTimeout(30_000);     // 30s read
        this.restTemplate.setRequestFactory(factory);
    }

    /**
     * Result record returned after AI evaluation.
     * isError=true means the result is due to an API failure, not a real evaluation.
     */
    public record ReviewResult(boolean approved, double score, String grade, String explanation, boolean isError) {}

    /**
     * Send item details + image to Gemini for expert review.
     * Retries up to MAX_RETRIES times on 429 rate-limit errors with exponential backoff.
     */
    public ReviewResult reviewItem(String itemName, String itemDescription, Path imagePath) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + MODEL + ":generateContent?key=" + apiKey;

        log.info("Using Gemini API URL: {}", url.replaceAll("key=.*", "key=***"));

        String prompt = buildPrompt(itemName, itemDescription);
        Map<String, Object> requestBody = buildRequestBody(prompt, imagePath);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity;
        try {
            entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
        } catch (Exception e) {
            log.error("Failed to serialize request for '{}': {}", itemName, e.getMessage());
            return new ReviewResult(false, 0.0, "F",
                    "Failed to prepare AI request: " + e.getMessage(), true);
        }

        // Retry loop with exponential backoff for 429 errors
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.info("Gemini API call attempt {}/{} for '{}'  [model={}]", attempt, MAX_RETRIES, itemName, MODEL);

                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.POST, entity, String.class);

                return parseResponse(response.getBody());

            } catch (HttpClientErrorException.TooManyRequests e) {
                long backoff = INITIAL_BACKOFF_MS * attempt;
                log.warn("Rate limited (429) on attempt {}/{} for '{}'. Retrying in {}s...",
                        attempt, MAX_RETRIES, itemName, backoff / 1000);

                if (attempt == MAX_RETRIES) {
                    log.error("All {} retries exhausted for '{}'", MAX_RETRIES, itemName);
                    return new ReviewResult(false, 0.0, "F",
                            "AI rate limit exceeded after " + MAX_RETRIES + " retries. Please retry later.", true);
                }

                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new ReviewResult(false, 0.0, "F", "Review interrupted.", true);
                }

            } catch (Exception e) {
                log.error("Gemini API call failed for '{}': {}", itemName, e.getMessage(), e);
                return new ReviewResult(false, 0.0, "F",
                        "Expert review error: " + e.getMessage(), true);
            }
        }

        return new ReviewResult(false, 0.0, "F", "AI review failed after retries.", true);
    }

    private Map<String, Object> buildRequestBody(String prompt, Path imagePath) {
        try {
            if (imagePath != null && Files.exists(imagePath)) {
                byte[] imageBytes = Files.readAllBytes(imagePath);
                String base64Image = Base64.getEncoder().encodeToString(imageBytes);
                String mimeType = determineMimeType(imagePath.toString());

                return Map.of(
                    "contents", List.of(Map.of(
                        "parts", List.of(
                            Map.of("text", prompt),
                            Map.of("inline_data", Map.of(
                                "mime_type", mimeType,
                                "data", base64Image
                            ))
                        )
                    )),
                    "generationConfig", Map.of(
                        "temperature", 0.3,
                        "maxOutputTokens", 1024
                    )
                );
            }
        } catch (IOException e) {
            log.warn("Could not read image file, falling back to text-only: {}", e.getMessage());
        }

        return Map.of(
            "contents", List.of(Map.of(
                "parts", List.of(Map.of("text", prompt))
            )),
            "generationConfig", Map.of(
                "temperature", 0.3,
                "maxOutputTokens", 1024
            )
        );
    }

    private String buildPrompt(String itemName, String itemDescription) {
        return """
            You are an expert authenticator for a collectibles and rare items auction platform.
            
            Evaluate the following item for authenticity and consistency between its name, description, and image.
            
            **Item Name:** %s
            **Item Description:** %s
            
            Please evaluate:
            1. Does the image match the item name and description?
            2. Does the item appear genuine/authentic based on visual inspection?
            3. Is the description accurate and consistent with what's shown?
            4. Overall quality assessment of the listing.
            
            Respond in EXACTLY this JSON format (no markdown, no code blocks, just raw JSON):
            {
              "decision": "APPROVED" or "REJECTED",
              "score": <number between 0 and 100>,
              "grade": "<A/B/C/D/F>",
              "explanation": "<2-3 sentence explanation of your decision>"
            }
            """.formatted(itemName, itemDescription);
    }

    private ReviewResult parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");

            if (candidates.isEmpty()) {
                log.warn("Gemini returned no candidates");
                return new ReviewResult(false, 0.0, "F", "AI returned no evaluation.", true);
            }

            String text = candidates.get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

            log.info("Gemini raw response: {}", text);

            // Clean up: remove markdown code fences if present
            text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            JsonNode result = objectMapper.readTree(text);

            String decision = result.path("decision").asText("REJECTED");
            double score = result.path("score").asDouble(0.0);
            String grade = result.path("grade").asText("F");
            String explanation = result.path("explanation").asText("No explanation provided.");

            boolean approved = "APPROVED".equalsIgnoreCase(decision);

            return new ReviewResult(approved, score, grade, explanation, false);

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage());
            return new ReviewResult(false, 0.0, "F",
                    "Could not parse AI response: " + e.getMessage(), true);
        }
    }

    private String determineMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg"; // default
    }
}
