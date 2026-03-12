package com.loopers.infrastructure.like;

import com.loopers.domain.like.ProductLikeSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProductLikeSummaryJpaRepository extends JpaRepository<ProductLikeSummary, Long> {

    @Modifying
    @Query(value = """
            REPLACE INTO product_like_summary (product_id, like_count, updated_at)
            SELECT l.product_id, COUNT(*), NOW()
            FROM likes l
            WHERE l.like_yn = 'Y'
            GROUP BY l.product_id
            """, nativeQuery = true)
    void refreshAll();
}
