package com.loopers.interfaces.api.brand.dto;

import com.loopers.application.brand.BrandInfo;
import com.loopers.domain.brand.BrandStatus;

import java.time.ZonedDateTime;

public class BrandV1Dto {

    public record BrandResponse(
            Long id,
            String name,
            String description,
            BrandStatus status,
            ZonedDateTime createdAt,
            ZonedDateTime updatedAt) {

        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(
                    info.id(),
                    info.name(),
                    info.description(),
                    info.status(),
                    info.createdAt(),
                    info.updatedAt());
        }
    }

    public record CreateBrandRequest(
            String name,
            String description) {
    }

    public record UpdateBrandRequest(
            String name,
            String description,
            BrandStatus status) {
    }
}
