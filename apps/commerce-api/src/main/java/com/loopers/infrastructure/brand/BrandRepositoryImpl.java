package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class BrandRepositoryImpl implements BrandRepository {

    private final BrandJpaRepository brandJpaRepository;

    @Override
    public Brand save(Brand brand) {
        return brandJpaRepository.save(brand);
    }

    @Override
    public Optional<Brand> findById(Long id) {
        return brandJpaRepository.findByIdAndDeletedAtIsNull(id);
    }

    @Override
    public boolean existsByName(String name) {
        return brandJpaRepository.existsByNameAndDeletedAtIsNull(name);
    }

    @Override
    public boolean existsByNameAndIdNot(String name, Long id) {
        return brandJpaRepository.existsByNameAndIdNotAndDeletedAtIsNull(name, id);
    }

    @Override
    public Page<Brand> findAll(Pageable pageable) {
        return brandJpaRepository.findAllByDeletedAtIsNull(pageable);
    }

    @Override
    public void softDelete(Long id) {
        brandJpaRepository.findById(id)
                .ifPresent(Brand::delete);
    }
}
