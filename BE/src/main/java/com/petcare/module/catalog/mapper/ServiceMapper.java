package com.petcare.module.catalog.mapper;

import com.petcare.module.catalog.dto.RequiredResourceItem;
import com.petcare.module.catalog.dto.ServiceResponse;
import com.petcare.module.catalog.entity.Service;
import com.petcare.module.catalog.entity.ServiceRequiredResource;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Service entity không có @OneToMany requiredResources (codebase không dùng JPA relation, FK
 * phẳng — xem module organization) nên {@code toResponse} nhận danh sách
 * {@link ServiceRequiredResource} rời, do ServiceCatalogServiceImpl tự truy vấn qua
 * ServiceRequiredResourceRepository rồi truyền vào.
 */
@Mapper(componentModel = "spring")
public interface ServiceMapper {

    // isActive: cùng vấn đề property-name mismatch của ProductMapper — map tường minh qua expression.
    @Mapping(target = "serviceId", source = "service.id")
    @Mapping(target = "requiredResources", source = "resources")
    @Mapping(target = "isActive", expression = "java(service.isActive())")
    ServiceResponse toResponse(Service service, List<ServiceRequiredResource> resources);

    RequiredResourceItem toItem(ServiceRequiredResource resource);

    default String map(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
