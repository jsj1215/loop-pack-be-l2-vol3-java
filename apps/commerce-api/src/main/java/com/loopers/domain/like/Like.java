package com.loopers.domain.like;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

@Getter
@Entity
@Table(name = "likes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_likes_member_product", columnNames = {"member_id", "product_id"})
})
public class Like {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "like_yn", nullable = false, length = 1)
    private String likeYn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    protected Like() {}

    private Like(Long memberId, Long productId, String likeYn) {
        this.memberId = memberId;
        this.productId = productId;
        this.likeYn = likeYn;
    }

    public static Like create(Long memberId, Long productId) {
        return new Like(memberId, productId, "Y");
    }

    @PrePersist
    private void prePersist() {
        ZonedDateTime now = ZonedDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = ZonedDateTime.now();
    }

    public void like() {
        this.likeYn = "Y";
    }

    public void unlike() {
        this.likeYn = "N";
    }

    public boolean isLiked() {
        return "Y".equals(this.likeYn);
    }
}
