package com.petcare.modules.users.entity;

import com.petcare.common.model.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_account_id", columnList = "account_id"),
    @Index(name = "idx_users_store_id", columnList = "store_id"),
    @Index(name = "idx_users_organization_id", columnList = "organization_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 500)
    private String avatar;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "store_id")
    private Long storeId;
}
