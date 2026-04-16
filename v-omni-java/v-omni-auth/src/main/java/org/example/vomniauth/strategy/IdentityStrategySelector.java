package org.example.vomniauth.strategy;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class IdentityStrategySelector {

    private final IdentityResolutionStrategy bloomHitStrategy;
    private final IdentityResolutionStrategy bloomMissStrategy;

    public IdentityStrategySelector(
            @Qualifier("bloomHitStrategy") IdentityResolutionStrategy bloomHitStrategy,
            @Qualifier("bloomMissStrategy") IdentityResolutionStrategy bloomMissStrategy) {
        this.bloomHitStrategy = bloomHitStrategy;
        this.bloomMissStrategy = bloomMissStrategy;
    }

    public IdentityResolutionStrategy select(boolean bloomContains) {
        return bloomContains ? bloomHitStrategy : bloomMissStrategy;
    }
}
