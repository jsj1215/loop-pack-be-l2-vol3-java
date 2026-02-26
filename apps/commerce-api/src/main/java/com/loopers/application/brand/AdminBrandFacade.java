package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandService;
import com.loopers.domain.brand.BrandStatus;
import com.loopers.domain.cart.CartService;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class AdminBrandFacade {

    private final BrandService brandService;
    private final ProductService productService;
    private final CartService cartService;

    public Page<BrandInfo> getBrands(Pageable pageable) {
        return brandService.findAll(pageable).map(BrandInfo::from);
    }

    public BrandInfo getBrand(Long brandId) {
        Brand brand = brandService.findById(brandId);
        return BrandInfo.from(brand);
    }

    @Transactional
    public BrandInfo createBrand(String name, String description) {
        Brand brand = brandService.register(name, description);
        return BrandInfo.from(brand);
    }

    @Transactional
    public BrandInfo updateBrand(Long brandId, String name, String description, BrandStatus status) {
        Brand brand = brandService.update(brandId, name, description, status);
        return BrandInfo.from(brand);
    }

    @Transactional
    public void deleteBrand(Long brandId) {
        Brand brand = brandService.findById(brandId);
        cartService.deleteByBrandId(brandId);
        productService.softDeleteByBrandId(brandId);
        brandService.softDelete(brandId);
    }
}
