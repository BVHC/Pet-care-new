package com.petcare.module.iam.service;

import com.petcare.module.iam.dto.PermissionResponse;
import com.petcare.module.iam.dto.RoleResponse;
import com.petcare.module.iam.mapper.IamMapper;
import com.petcare.module.iam.repository.PermissionRepository;
import com.petcare.module.iam.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleCatalogServiceImpl implements RoleCatalogService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final IamMapper iamMapper;

    @Override
    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return roleRepository.findAll().stream().map(iamMapper::toRoleResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionResponse> listPermissions() {
        return permissionRepository.findAll().stream().map(iamMapper::toPermissionResponse).toList();
    }
}
