package com.loopers.domain.brand;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "brand")
public class Brand extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BrandStatus status;

    protected Brand() {}

    public Brand(String name, String description) {
        this.name = name;
        this.description = description;
        this.status = BrandStatus.PENDING;
    }

    public void updateInfo(String name, String description, BrandStatus status) {
        this.name = name;
        this.description = description;
        this.status = status;
    }

    public void changeStatus(BrandStatus status) {
        this.status = status;
    }

    public boolean isActive() {
        return this.status == BrandStatus.ACTIVE;
    }
}
