package com.petcare.module.iam.service;

import com.petcare.module.iam.dto.PermissionResponse;
import com.petcare.module.iam.dto.RoleResponse;

import java.util.List;

/**
 * Module 02 (IAM) — ManageRole/ManagePermission (đọc catalog),
 * docs/api/iam-v1.md A12/A13. Chỉ đọc — không có create/update trong phạm vi
 * này (custom role bị hoãn, xem plan mục A).
 */
public interface RoleCatalogService {

    List<RoleResponse> listRoles();

    List<PermissionResponse> listPermissions();
}
