package com.loopers.domain.brand;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface BrandRepository {

    Brand save(Brand brand);

    Optional<Brand> findById(Long id);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    Page<Brand> findAll(Pageable pageable);

    void softDelete(Long id);
}
