package com.loopers.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@EnableCaching
@Configuration
public class LocalCacheConfig {

    /**
     * 로컬 캐시 사용
     * @return
     */
    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(List.of(
                buildCache("productSearch", Duration.ofMinutes(3), 1_000), // 상품 목록 조회용
                buildCache("productDetail", Duration.ofMinutes(5), 5_000)  // 상품 상세 조회용

                // TTL의 경우,
                // maxSize의 경우, 여러 개의 상품 상세 정보를 캐시하기 위해 상품 리스트 보다 상품 상세가 양이 더 많게 설정했음.
        ));
        return cacheManager;
    }

    private CaffeineCache buildCache(String name, Duration ttl, long maxSize) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}
