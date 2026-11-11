package com.petcare.module.catalog.api;

import com.petcare.module.customer.api.Species;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Owner: catalog (SP) · BE-2. Chỉ đọc danh mục sản phẩm, dịch vụ, loại chuồng, loại vaccine, phác đồ. */
public interface CatalogQueryApi {

    record ServiceInfo(Long serviceId, String name, ServiceGroup group, MedicalType medicalType,
                       long price, boolean active) {}

    record ProductInfo(Long productId, String name, ProductType type, boolean prescription,
                       boolean tracksExpiry, Long vaccineTypeId, long price, boolean active) {}

    /** Loại chuồng là Service nhóm BOARDING; giá đêm = giá của Service (BR-SP-04). */
    record KennelTypeInfo(Long serviceId, String name, Species species, BigDecimal maxWeightKg,
                          long nightlyPrice, boolean active) {}

    record ProtocolDose(Long protocolId, Species species, Long vaccineTypeId, int doseNumber,
                        int intervalDays, int minAgeWeeks, boolean requiredForBoarding, boolean active) {}

    Optional<ServiceInfo> findService(Long serviceId);

    List<ServiceInfo> findServices(Collection<Long> serviceIds);

    /** Mọi dịch vụ đang kinh doanh (kể cả loại chuồng), theo tên: branch UC33 ghép với cờ bật / tắt tại chi nhánh. */
    List<ServiceInfo> listActiveServices();

    Optional<ProductInfo> findProduct(Long productId);

    /** Nhiều sản phẩm một lần (id không tồn tại bị bỏ qua), để bên gọi tra tên mà không N+1 (thêm 2026-10-16, T18). */
    List<ProductInfo> findProducts(Collection<Long> productIds);

    /**
     * Như {@link #findProduct} nhưng khóa chia sẻ dòng sản phẩm ({@code SELECT … FOR SHARE}); thêm 2026-10-16 (T18,
     * docs/adr/0042). {@code MANDATORY}: chạy trong transaction của bên gọi. Bên sắp ghi dữ liệu phụ thuộc cờ
     * {@code tracksExpiry} (xác nhận phiếu nhập kho tạo lô có hạn) phải đọc qua method này: catalog sửa sản phẩm khóa
     * ghi dòng trước khi đọc, nên tắt cờ chạy song song bị xếp hàng và thấy lô vừa tạo.
     */
    Optional<ProductInfo> findProductForShare(Long productId);

    Optional<KennelTypeInfo> findKennelType(Long serviceId);

    Optional<ProtocolDose> findProtocolDose(Long protocolId);

    /** Các dòng phác đồ đang dùng của một loài × loại vaccine, theo mũi tăng dần (BR-SP-02, BR-KB-04). */
    List<ProtocolDose> findProtocol(Species species, Long vaccineTypeId);

    /** Loại vaccine có ít nhất 1 dòng phác đồ cờ bắt buộc khi lưu trú của loài (BR-LT-05). */
    List<Long> findBoardingRequiredVaccineTypeIds(Species species);

    /**
     * Tên loại vaccine theo lô (thêm 2026-10-22, T31): boarding ghép tên cho {@code VaccineGap}. Id không có thì vắng
     * mặt trong kết quả; tập rỗng → map rỗng. Đọc mới mỗi lần gọi.
     */
    java.util.Map<Long, String> findVaccineTypeNames(Collection<Long> vaccineTypeIds);

    /** Loại vaccine và loài áp dụng (BR-SP-07). Thêm 2026-10-25 bởi BE-1 (visit #18, docs/adr/0073). */
    record VaccineTypeInfo(Long vaccineTypeId, String name, Species species, boolean active) {}

    /** Một loại vaccine, không lọc {@code active} (bên gọi tự xét "ngừng sử dụng"). Đọc mới mỗi lần gọi. */
    Optional<VaccineTypeInfo> findVaccineType(Long vaccineTypeId);

    /**
     * Mọi sản phẩm gắn loại vaccine, <b>kể cả đã ngừng kinh doanh</b> (bên gọi tự lọc), theo tên rồi {@code productId}.
     * Thêm 2026-10-25 bởi BE-1 (visit #18 gợi ý sản phẩm còn tồn, docs/adr/0073).
     */
    List<ProductInfo> findProductsByVaccineType(Long vaccineTypeId);

    // ------------------------------------------------------------------------------------------------------------
    // Tầng 3 cho module ai (07 I-3). Placeholder T55c đã được thay bằng bản thật ở AI-0 (9/11/2026).
    // ------------------------------------------------------------------------------------------------------------

    /** Nguồn của gợi ý (07 I-3): đều đang kinh doanh; sản phẩm chỉ {@code GOODS} không kê đơn. */
    record Recommendable(List<ServiceInfo> groomingServices, List<KennelTypeInfo> kennelTypes,
                         List<ProductInfo> products) {}

    /**
     * I-3 (07 §11, UC91, ST22): mọi dòng phác đồ <b>đang dùng</b> của loài, mọi loại vaccine, theo loại vaccine rồi mũi
     * tăng dần.
     */
    List<ProtocolDose> listActiveProtocols(Species species);

    /** I-3 (07 §11, UC92): dịch vụ thẩm mỹ, loại chuồng và sản phẩm {@code GOODS} không kê đơn đang kinh doanh. */
    Recommendable listRecommendable();

    /** Danh mục và ảnh của một sản phẩm, để gợi ý ghép "cùng danh mục" (07 §7.3) và hiển thị (BR-AI-22). */
    record ProductDisplay(Long categoryId, String imageUrl) {}

    /**
     * {@code priceFromServiceIds}: trong các dịch vụ đã cho, những dịch vụ hiển thị giá dạng "Từ X đ" ({@code price_is_from},
     * BR-CK-02); {@code products}: {@code productId → ProductDisplay}.
     */
    record RecommendDisplay(java.util.Set<Long> priceFromServiceIds, java.util.Map<Long, ProductDisplay> products) {}

    /**
     * Thêm 2026-11-11 bởi FE-1 làm thay BE-2 (T78, docs/adr/0133, Hiến duyệt): {@link #listRecommendable} không mang
     * {@code categoryId}, {@code imageUrl} của sản phẩm và cờ {@code price_is_from} của dịch vụ. Đọc theo lô, chỉ thêm
     * method (không đổi record hay chữ ký nào); id không tồn tại thì vắng mặt; tập rỗng thì kết quả rỗng, không chạy SQL.
     */
    RecommendDisplay findRecommendDisplay(Collection<Long> serviceIds, Collection<Long> productIds);
}
