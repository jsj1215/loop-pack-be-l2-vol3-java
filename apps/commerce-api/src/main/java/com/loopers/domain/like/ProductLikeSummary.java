package com.loopers.domain.like;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Entity
@Table(name = "product_like_summary")
public class ProductLikeSummary {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    protected ProductLikeSummary() {}

    public ProductLikeSummary(Long productId, int likeCount) {
        this.productId = productId;
        this.likeCount = likeCount;
        this.updatedAt = ZonedDateTime.now();
    }
}
