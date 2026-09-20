package com.petcare.module.iam.mapper;

import com.petcare.module.iam.dto.PermissionResponse;
import com.petcare.module.iam.dto.RoleAssignmentResponse;
import com.petcare.module.iam.dto.RoleResponse;
import com.petcare.module.iam.dto.UserResponse;
import com.petcare.module.iam.entity.Permission;
import com.petcare.module.iam.entity.Role;
import com.petcare.module.iam.entity.User;
import com.petcare.platform.enums.AccountStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface IamMapper {

    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "status", source = "status")
    UserResponse toUserResponse(User user, AccountStatus status);

    @Mapping(target = "userId", source = "id")
    RoleAssignmentResponse toRoleAssignmentResponse(User user);

    @Mapping(target = "roleId", source = "id")
    RoleResponse toRoleResponse(Role role);

    PermissionResponse toPermissionResponse(Permission permission);
}
