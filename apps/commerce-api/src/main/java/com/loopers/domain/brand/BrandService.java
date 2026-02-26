package com.loopers.domain.brand;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
@RequiredArgsConstructor
@Component
public class BrandService {

    private final BrandRepository brandRepository;

    public Brand register(String name, String description) {
        if (brandRepository.existsByName(name)) {
            throw new CoreException(ErrorType.CONFLICT, "이미 존재하는 브랜드명입니다.");
        }

        Brand brand = new Brand(name, description);
        return brandRepository.save(brand);
    }

    public Brand findById(Long brandId) {
        return brandRepository.findById(brandId)
                .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다."));
    }

    public Brand findActiveBrand(Long brandId) {
        Brand brand = findById(brandId);
        if (!brand.isActive()) {
            throw new CoreException(ErrorType.NOT_FOUND, "브랜드를 찾을 수 없습니다.");
        }
        return brand;
    }

    public Brand update(Long brandId, String name, String description, BrandStatus status) {
        Brand brand = findById(brandId);

        if (brandRepository.existsByNameAndIdNot(name, brandId)) {
            throw new CoreException(ErrorType.CONFLICT, "이미 존재하는 브랜드명입니다.");
        }

        brand.updateInfo(name, description, status);
        return brandRepository.save(brand);
    }

    public void softDelete(Long brandId) {
        findById(brandId);
        brandRepository.softDelete(brandId);
    }

    public Page<Brand> findAll(Pageable pageable) {
        return brandRepository.findAll(pageable);
    }
}
