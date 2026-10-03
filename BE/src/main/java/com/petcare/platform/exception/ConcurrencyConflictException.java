package com.petcare.platform.exception;

/**
 * Dữ liệu bị thao tác khác thay đổi đồng thời (khóa, tranh chấp chỗ cuối cùng...). Client nên tải lại rồi thử lại.
 */
public class ConcurrencyConflictException extends PlatformException {

    private final String resourceType;
    private final transient Object resourceId;

    public ConcurrencyConflictException(String resourceType, Object resourceId) {
        super(ErrorCode.CONCURRENCY_CONFLICT,
                resourceType + " #" + resourceId + " vừa bị thay đổi bởi thao tác khác, vui lòng tải lại và thử lại");
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
