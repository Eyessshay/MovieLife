package com.movielife.service;

import com.movielife.enums.AiSummaryTargetType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ReviewSummaryTargetPolicyRegistry {

    private final Map<AiSummaryTargetType, ReviewSummaryTargetPolicy> policies =
            new EnumMap<>(AiSummaryTargetType.class);

    public ReviewSummaryTargetPolicyRegistry(List<ReviewSummaryTargetPolicy> policyList) {
        for (ReviewSummaryTargetPolicy policy : policyList) {
            ReviewSummaryTargetPolicy previous = policies.put(policy.targetType(), policy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate AI summary target policy: "
                        + policy.targetType());
            }
        }
        for (AiSummaryTargetType targetType : AiSummaryTargetType.values()) {
            if (!policies.containsKey(targetType)) {
                throw new IllegalStateException("Missing AI summary target policy: " + targetType);
            }
        }
    }

    public ReviewSummaryTargetPolicy require(AiSummaryTargetType targetType) {
        ReviewSummaryTargetPolicy policy = policies.get(targetType);
        if (policy == null) {
            throw new IllegalArgumentException("Unsupported AI summary target type");
        }
        return policy;
    }
}
