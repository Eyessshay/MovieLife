package com.movielife.service;

import com.movielife.ai.ReviewSummaryInputItem;
import com.movielife.ai.ReviewSummarySnapshot;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class ReviewSummaryFingerprintCalculator {

    public String calculate(ReviewSummarySnapshot snapshot, String promptVersion) {
        if (snapshot == null || promptVersion == null || promptVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("snapshot and promptVersion are required");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, snapshot.getTargetType().name());
            write(out, String.valueOf(snapshot.getTargetId()));
            write(out, promptVersion);
            write(out, String.valueOf(snapshot.getSourceReviewCount()));
            write(out, String.valueOf(snapshot.getInputReviewCount()));
            for (ReviewSummaryInputItem review : snapshot.getReviews()) {
                write(out, String.valueOf(review.getReviewId()));
                write(out, review.getScore());
                write(out, review.getContent());
            }
            return toHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("cannot build review summary fingerprint", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void write(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private String toHex(byte[] value) {
        StringBuilder hex = new StringBuilder(value.length * 2);
        for (byte item : value) {
            hex.append(String.format("%02x", item & 0xff));
        }
        return hex.toString();
    }
}
