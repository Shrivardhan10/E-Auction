package com.eauction.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Pure-Java image similarity service using colour-histogram comparison.
 * No external libraries required — uses only java.awt and javax.imageio.
 *
 * Algorithm:
 *   1. Resize both images to a common 128×128 canvas
 *   2. Compute a normalised RGB colour histogram (64 bins per channel = 192 bins)
 *   3. Calculate cosine similarity between the two histogram vectors
 *   4. Return a percentage 0–100
 */
@Service
public class ImageSimilarityService {

    private static final Logger log = LoggerFactory.getLogger(ImageSimilarityService.class);

    private static final int RESIZE_DIM = 128;      // resize target
    private static final int BINS_PER_CHANNEL = 64;  // histogram resolution
    private static final int TOTAL_BINS = BINS_PER_CHANNEL * 3; // R + G + B

    /**
     * Compare two images and return a similarity score between 0 and 100.
     *
     * @param imageData1 raw bytes of the first image  (e.g. seller's listing photo)
     * @param imageData2 raw bytes of the second image (e.g. delivery pickup photo)
     * @return similarity percentage (0–100). Returns 0 if either image is null / unreadable.
     */
    public double calculateSimilarity(byte[] imageData1, byte[] imageData2) {
        if (imageData1 == null || imageData2 == null
                || imageData1.length == 0 || imageData2.length == 0) {
            log.warn("One or both images are null/empty — returning 0% similarity");
            return 0.0;
        }

        try {
            BufferedImage img1 = ImageIO.read(new ByteArrayInputStream(imageData1));
            BufferedImage img2 = ImageIO.read(new ByteArrayInputStream(imageData2));

            if (img1 == null || img2 == null) {
                log.warn("ImageIO could not decode one of the images — returning 0%");
                return 0.0;
            }

            BufferedImage resized1 = resize(img1, RESIZE_DIM, RESIZE_DIM);
            BufferedImage resized2 = resize(img2, RESIZE_DIM, RESIZE_DIM);

            double[] hist1 = computeHistogram(resized1);
            double[] hist2 = computeHistogram(resized2);

            double similarity = cosineSimilarity(hist1, hist2) * 100.0;

            log.info("Image similarity computed: {:.2f}%", similarity);
            return Math.max(0.0, Math.min(100.0, similarity));

        } catch (IOException e) {
            log.error("Error computing image similarity: {}", e.getMessage(), e);
            return 0.0;
        }
    }

    // ─── internal helpers ───────────────────────────────────────────────

    private BufferedImage resize(BufferedImage src, int width, int height) {
        BufferedImage dest = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return dest;
    }

    /**
     * Builds a normalised histogram with {@code BINS_PER_CHANNEL} bins for each of R, G, B.
     */
    private double[] computeHistogram(BufferedImage img) {
        double[] histogram = new double[TOTAL_BINS];
        int w = img.getWidth();
        int h = img.getHeight();
        int totalPixels = w * h;
        int binSize = 256 / BINS_PER_CHANNEL;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                histogram[r / binSize]++;                                       // red
                histogram[BINS_PER_CHANNEL + g / binSize]++;                    // green
                histogram[2 * BINS_PER_CHANNEL + b / binSize]++;                // blue
            }
        }

        // normalise
        for (int i = 0; i < histogram.length; i++) {
            histogram[i] /= totalPixels;
        }

        return histogram;
    }

    /**
     * Cosine similarity: dot(a, b) / (||a|| · ||b||).  Returns 0–1.
     */
    private double cosineSimilarity(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0.0 : dot / denom;
    }
}
