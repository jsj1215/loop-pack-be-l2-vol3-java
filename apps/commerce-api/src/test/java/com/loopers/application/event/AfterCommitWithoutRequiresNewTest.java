package com.loopers.application.event;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.product.MarginType;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductOption;
import com.loopers.domain.product.ProductService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [통합 테스트 - AFTER_COMMIT 리스너의 DB 쓰기 동작 검증]
 *
 * 테스트 대상: AFTER_COMMIT 시점에서 REQUIRES_NEW 없이 DB 쓰기가 가능한지 검증
 * 테스트 유형: 통합 테스트 (Integration Test)
 *
 * @TransactionalEventListener(AFTER_COMMIT) 시점에는 기존 트랜잭션이 커밋된 상태이지만
 * JDBC 커넥션은 아직 반환되지 않았고, auto-commit 모드로 전환된다.
 * 이 테스트는 해당 동작을 TransactionSynchronization 콜백으로 재현한다.
 */
@SpringBootTest
class AfterCommitWithoutRequiresNewTest {

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ProductService productService;

    @Autowired
    private BrandService brandService;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Product createProduct() {
        return transactionTemplate.execute(status -> {
            Brand brand = brandService.register("나이키", "스포츠 브랜드");
            brand = brandService.update(brand.getId(), "나이키", "스포츠 브랜드", BrandStatus.ACTIVE);
            ProductOption option = new ProductOption(null, "사이즈 270", 100);
            return productService.register(brand, "에어맥스", 100000, MarginType.RATE, 10,
                    0, 3000, "에어맥스 설명", List.of(option));
        });
    }

    @DisplayName("AFTER_COMMIT 시점에 REQUIRES_NEW 없이도 DB 쓰기가 auto-commit으로 반영된다")
    @Test
    void afterCommit_withoutRequiresNew_dbWriteSucceedsByAutoCommit() {
        // given
        Product product = createProduct();

        // when - AFTER_COMMIT 콜백에서 REQUIRES_NEW 없이 직접 DB UPDATE
        transactionTemplate.execute(status -> {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            // REQUIRES_NEW 없이 JDBC auto-commit으로 실행
                            jdbcTemplate.update(
                                    "UPDATE product SET like_count = like_count + 1 WHERE id = ?",
                                    product.getId()
                            );
                        }
                    }
            );
            return null;
        });

        // then - auto-commit 모드에서 UPDATE가 반영됨
        Integer likeCount = jdbcTemplate.queryForObject(
                "SELECT like_count FROM product WHERE id = ?",
                Integer.class, product.getId()
        );
        assertThat(likeCount).isEqualTo(1);
    }

    @DisplayName("REQUIRES_NEW 없이 2개의 UPDATE 중 두 번째가 실패하면, 첫 번째는 이미 반영되어 부분 커밋이 발생한다")
    @Test
    void afterCommit_withoutRequiresNew_partialCommitOnFailure() {
        // given
        Product product = createProduct();

        // when - AFTER_COMMIT 콜백에서 2개의 UPDATE, 두 번째에서 예외 발생
        try {
            transactionTemplate.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                // 첫 번째 UPDATE: like_count +1 (성공, 즉시 auto-commit)
                                jdbcTemplate.update(
                                        "UPDATE product SET like_count = like_count + 1 WHERE id = ?",
                                        product.getId()
                                );
                                // 두 번째 UPDATE: 존재하지 않는 컬럼으로 예외 발생
                                jdbcTemplate.update(
                                        "UPDATE product SET non_existent_column = 1 WHERE id = ?",
                                        product.getId()
                                );
                            }
                        }
                );
                return null;
            });
        } catch (Exception ignored) {
            // AFTER_COMMIT 콜백의 예외가 호출자에게 전파됨
        }

        // then - 첫 번째 UPDATE는 이미 auto-commit으로 반영됨 (부분 커밋)
        Integer likeCount = jdbcTemplate.queryForObject(
                "SELECT like_count FROM product WHERE id = ?",
                Integer.class, product.getId()
        );
        assertThat(likeCount).isEqualTo(1);
    }
}
