package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class BrandFacade {

    private final BrandService brandService;

    /**
     * 브랜드 조회
     * @param brandId
     * @return
     */
    public BrandInfo getBrand(Long brandId) {
        Brand brand = brandService.findActiveBrand(brandId);
        return BrandInfo.from(brand);
    }
}
