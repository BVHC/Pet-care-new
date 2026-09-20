package com.petcare.module.organization.service;

import com.petcare.module.organization.dto.ConfigureOperatingHoursRequest;
import com.petcare.module.organization.dto.OperatingHoursResponse;
import com.petcare.platform.security.UserPrincipal;

import java.util.UUID;

public interface OperatingHourService {

    OperatingHoursResponse configureOperatingHours(UUID storeId, ConfigureOperatingHoursRequest request,
                                                    UserPrincipal actor);
}
