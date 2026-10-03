package com.petcare.platform.exception;

public class ResourceNotFoundException extends PlatformException {

    private final String resourceType;
    private final transient Object resourceId;

    public ResourceNotFoundException(String resourceType, Object resourceId) {
        super(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy " + resourceType + " #" + resourceId);
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public Object getResourceId() {
        return resourceId;
    }
}
