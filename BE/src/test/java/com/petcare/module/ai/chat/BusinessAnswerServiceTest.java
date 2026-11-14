package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.ServiceGroup;

/**
 * Câu mẫu giá dịch vụ và chi nhánh từ CSDL (BR-AI-31, docs/adr/0134 "Đã cài ở T90"), với {@code CatalogQueryApi} và
 * {@code BranchQueryApi} giả. Dịch vụ công khai = đang kinh doanh và bật ở ít nhất một chi nhánh ACTIVE (đúng tập của trang
 * bảng giá công khai); không khớp tên thì câu cố định, không bịa giá.
 */
class BusinessAnswerServiceTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);

    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final BranchQueryApi branches = mock(BranchQueryApi.class);
    private final Clock clock = Clock.fixed(TODAY.atTime(9, 0).atZone(VN).toInstant(), VN);
    private final Map<Long, List<Long>> enabledAt = new HashMap<>();
    private BusinessAnswerService service;

    @BeforeEach
    void setUp() {
        when(branches.findActiveBranchIdsEnablingServices(any())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            Map<Long, List<Long>> result = new HashMap<>();
            ids.forEach(id -> result.put(id, enabledAt.getOrDefault(id, List.of())));
            return result;
        });
        service = new BusinessAnswerService(catalog, branches, clock);
    }

    private ServiceInfo sold(long id, String name, long price) {
        enabledAt.put(id, List.of(1L));
        return new ServiceInfo(id, name, ServiceGroup.GROOMING, null, price, true);
    }

    private void catalogHas(ServiceInfo... services) {
        when(catalog.listActiveServices()).thenReturn(List.of(services));
    }

    private static BranchSummary branch(long id, String name, BranchStatus status, boolean emergency) {
        return new BranchSummary(id, name, "12 Âu Cơ, Tây Hồ", "0912345678", status, emergency);
    }

    private String answer(String question) {
        return service.answer(question).orElseThrow();
    }

    @Test
    void aPriceQuestionListsTheMatchingServiceWithTheVietnameseMoneyFormat() {
        catalogHas(sold(1, "Khám tổng quát", 1_234_000), sold(2, "Tắm & sấy", 120_000));

        assertThat(answer("Giá tắm cho mèo bao nhiêu?")).contains("Tắm & sấy: 120.000 ₫").doesNotContain("Khám tổng quát");
        assertThat(answer("chi phí khám tổng quát")).contains("Khám tổng quát: 1.234.000 ₫");
    }

    @Test
    void matchingIgnoresAccentsAndCase() {
        catalogHas(sold(1, "Cắt tỉa lông", 250_000));

        assertThat(answer("BAO NHIEU TIEN cat tia long cho cho")).contains("Cắt tỉa lông: 250.000 ₫");
    }

    @Test
    void theHeadWordOfAServiceNameIsEnoughToFindIt() {
        catalogHas(sold(1, "Khám tổng quát", 150_000), sold(2, "Tắm & sấy", 120_000));

        assertThat(answer("giá khám bao nhiêu")).contains("Khám tổng quát: 150.000 ₫");
    }

    @Test
    void severalMatchesAreListedUpToThree() {
        catalogHas(sold(1, "Chuồng mèo", 180_000), sold(2, "Chuồng chó nhỏ", 200_000), sold(3, "Chuồng chó lớn", 320_000),
                sold(4, "Chuồng thỏ", 150_000));

        String text = answer("Giá chuồng bao nhiêu");

        assertThat(text.lines().filter(line -> line.startsWith("- "))).hasSize(3);
    }

    @Test
    void noMatchGivesTheFixedSentenceAndNeverAPrice() {
        catalogHas(sold(1, "Khám tổng quát", 150_000));

        assertThat(answer("Giá massage bao nhiêu?")).isEqualTo(ChatTexts.BUSINESS_NO_PRICE);
    }

    @Test
    void aServiceThatIsNotActiveIsNeverQuoted() {
        enabledAt.put(9L, List.of(1L));
        catalogHas(new ServiceInfo(9L, "Spa thú cưng", ServiceGroup.GROOMING, null, 500_000, false));

        assertThat(answer("giá spa thú cưng")).isEqualTo(ChatTexts.BUSINESS_NO_PRICE);
    }

    @Test
    void aServiceEnabledAtNoActiveBranchIsNotPublicSoItIsNotQuoted() {
        catalogHas(new ServiceInfo(5L, "Chuồng mèo", ServiceGroup.BOARDING, null, 180_000, true));

        assertThat(answer("giá chuồng mèo")).isEqualTo(ChatTexts.BUSINESS_NO_PRICE);
    }

    @Test
    void aBareFeeWordNeedsAServiceNameAndOtherwiseIsNotABusinessQuestion() {
        catalogHas(sold(2, "Tắm & sấy", 120_000));

        assertThat(answer("phí tắm mèo")).contains("Tắm & sấy: 120.000 ₫");
        assertThat(service.answer("Đừng lãng phí thời gian của mèo")).isEmpty();
        assertThat(service.answer("phí massage")).isEmpty();
    }

    @Test
    void aQuestionWithoutBusinessIntentTouchesNothing() {
        assertThat(service.answer("Chó mèo nên ăn mấy bữa một ngày?")).isEmpty();
        assertThat(service.answer("Giá trị dinh dưỡng của hạt là gì?")).isEmpty();

        verifyNoInteractions(catalog, branches);
    }

    @Test
    void aBranchQuestionListsActiveBranchesWithAddressPhoneAndTodayHours() {
        when(branches.listActiveBranches()).thenReturn(List.of(branch(1, "PetCare Tây Hồ", BranchStatus.ACTIVE, true),
                branch(2, "Chi nhánh nháp", BranchStatus.DRAFT, true)));
        when(branches.openingRangesOn(1L, TODAY)).thenReturn(List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                new TimeRange(LocalTime.of(13, 30), LocalTime.of(17, 30))));

        String text = answer("Chi nhánh Pet Care ở đâu?");

        assertThat(text).contains("PetCare Tây Hồ").contains("12 Âu Cơ, Tây Hồ").contains("0912345678")
                .contains("08:00–12:00, 13:30–17:30").contains("cấp cứu ngoài giờ").doesNotContain("Chi nhánh nháp");
    }

    @Test
    void aBranchClosedTodayAndWithoutEmergencySaysSo() {
        when(branches.listActiveBranches()).thenReturn(List.of(branch(1, "PetCare Tây Hồ", BranchStatus.ACTIVE, false)));
        when(branches.openingRangesOn(1L, TODAY)).thenReturn(List.of());

        assertThat(answer("giờ mở cửa của phòng khám")).contains("Hôm nay nghỉ").doesNotContain("cấp cứu");
    }

    @Test
    void noActiveBranchGivesAFixedSentence() {
        when(branches.listActiveBranches()).thenReturn(List.of());

        assertThat(answer("địa chỉ phòng khám")).isEqualTo(ChatTexts.BUSINESS_NO_BRANCH);
    }

    @Test
    void aQuestionAboutBothAnswersBothPriceFirst() {
        catalogHas(sold(2, "Tắm & sấy", 120_000));
        when(branches.listActiveBranches()).thenReturn(List.of(branch(1, "PetCare Tây Hồ", BranchStatus.ACTIVE, false)));
        when(branches.openingRangesOn(1L, TODAY)).thenReturn(List.of(new TimeRange(LocalTime.of(8, 0), LocalTime.of(17, 0))));

        String text = answer("Giá tắm bao nhiêu và chi nhánh ở đâu?");

        assertThat(text).contains("Tắm & sấy: 120.000 ₫").contains("PetCare Tây Hồ");
        assertThat(text.indexOf("Tắm & sấy")).isLessThan(text.indexOf("PetCare Tây Hồ"));
        assertThat(Set.of(text.split("\n\n"))).hasSize(2);
    }
}
