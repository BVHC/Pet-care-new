package com.petcare.module.ai.chat;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.branch.api.BranchQueryApi.TimeRange;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;

/**
 * Câu mẫu giá dịch vụ và chi nhánh dựng từ CSDL, không qua embedding hay LLM (BR-AI-31, docs/adr/0134 "Đã cài ở T90").
 * Chỉ qua {@link CatalogQueryApi} và {@link BranchQueryApi} (I-3, I-4). Dịch vụ công khai là dịch vụ đang kinh doanh và bật
 * ở ít nhất một chi nhánh ACTIVE: đúng tập mà trang bảng giá công khai trả. Khớp tên ở đây thay vì {@code pg_trgm} như đề
 * xuất I-11: V33 không có chỉ mục trigram cho {@code services}, danh mục chỉ vài chục dịch vụ.
 */
@Service
class BusinessAnswerService {

    private static final int MAX_SERVICES = 3;
    /** Điểm tối thiểu (số từ của tên có trong câu hỏi / số từ của tên) khi từ đầu của tên không có trong câu. */
    private static final double MIN_SCORE = 0.5;
    /** Từ đệm của tên dịch vụ, không dùng để khớp. */
    private static final Set<String> NAME_STOP_WORDS = Set.of("dich", "vu", "va");

    private final CatalogQueryApi catalog;
    private final BranchQueryApi branches;
    private final Clock clock;

    BusinessAnswerService(CatalogQueryApi catalog, BranchQueryApi branches, Clock clock) {
        this.catalog = catalog;
        this.branches = branches;
        this.clock = clock;
    }

    /** Rỗng nếu câu hỏi không có ý định nghiệp vụ (hoặc chỉ có "phí" mà không nhắc dịch vụ nào): bên gọi đi tiếp đường cũ. */
    Optional<String> answer(String question) {
        boolean branch = BusinessIntents.isBranch(question);
        boolean price = BusinessIntents.isPrice(question);
        boolean weak = BusinessIntents.mentionsWeakPrice(question);
        List<ServiceInfo> matched = price || weak ? matchServices(question) : List.of();
        boolean asksPrice = price || !matched.isEmpty();
        if (!branch && !asksPrice) {
            return Optional.empty();
        }
        List<String> parts = new ArrayList<>();
        if (asksPrice) {
            parts.add(priceAnswer(matched));
        }
        if (branch) {
            parts.add(branchAnswer());
        }
        return Optional.of(String.join("\n\n", parts));
    }

    private static String priceAnswer(List<ServiceInfo> matched) {
        if (matched.isEmpty()) {
            return ChatTexts.BUSINESS_NO_PRICE;
        }
        StringBuilder text = new StringBuilder(ChatTexts.BUSINESS_PRICE_HEADER);
        for (ServiceInfo service : matched) {
            text.append("\n- ").append(service.name()).append(": ").append(vnd(service.price()));
        }
        return text.append('\n').append(ChatTexts.BUSINESS_PRICE_NOTE).toString();
    }

    private String branchAnswer() {
        List<BranchSummary> active = branches.listActiveBranches().stream()
                .filter(branch -> branch.status() == BranchStatus.ACTIVE).toList();
        if (active.isEmpty()) {
            return ChatTexts.BUSINESS_NO_BRANCH;
        }
        LocalDate today = LocalDate.now(clock);
        StringBuilder text = new StringBuilder(ChatTexts.BUSINESS_BRANCH_HEADER);
        for (BranchSummary branch : active) {
            text.append("\n- ").append(branch.name());
            if (branch.address() != null) {
                text.append(": ").append(branch.address()).append('.');
            }
            if (branch.phone() != null) {
                text.append(" SĐT ").append(branch.phone()).append('.');
            }
            text.append(' ').append(hoursToday(branches.openingRangesOn(branch.branchId(), today)));
            if (branch.acceptsAfterHoursEmergency()) {
                text.append(" Nhận cấp cứu ngoài giờ.");
            }
        }
        return text.toString();
    }

    private static String hoursToday(List<TimeRange> ranges) {
        return ranges.isEmpty() ? "Hôm nay nghỉ." : "Hôm nay: " + ranges.stream()
                .map(range -> range.open() + "–" + range.close()).collect(Collectors.joining(", ")) + ".";
    }

    /** Tối đa {@value #MAX_SERVICES} dịch vụ công khai khớp tên, điểm cao trước rồi theo tên. */
    private List<ServiceInfo> matchServices(String question) {
        List<ServiceInfo> active = catalog.listActiveServices().stream().filter(ServiceInfo::active).toList();
        if (active.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Long>> enabledAt = branches.findActiveBranchIdsEnablingServices(
                active.stream().map(ServiceInfo::serviceId).toList());
        Set<String> asked = Set.copyOf(tokens(question));
        record Scored(ServiceInfo service, double score) {
        }
        return active.stream()
                .filter(service -> !enabledAt.getOrDefault(service.serviceId(), List.of()).isEmpty())
                .map(service -> new Scored(service, score(asked, service.name())))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(scored -> scored.service().name()))
                .limit(MAX_SERVICES).map(Scored::service).toList();
    }

    /** 0 nếu không khớp; ngược lại tỉ lệ từ của tên có trong câu hỏi (từ đầu của tên có trong câu thì đủ để khớp). */
    private static double score(Set<String> asked, String serviceName) {
        List<String> name = tokens(serviceName).stream().filter(token -> !NAME_STOP_WORDS.contains(token)).toList();
        if (name.isEmpty()) {
            return 0;
        }
        double score = (double) name.stream().filter(asked::contains).count() / name.size();
        boolean headMatches = asked.contains(name.get(0));
        return score >= MIN_SCORE || headMatches ? score : 0;
    }

    /** Chữ thường, bỏ dấu, tách theo mọi ký tự không phải chữ hoặc số. */
    private static List<String> tokens(String text) {
        return Arrays.stream(PhraseMatcher.strip(PhraseMatcher.norm(text)).split("[^a-z0-9]+"))
                .filter(token -> !token.isEmpty()).toList();
    }

    /** {@code 1.234.000 ₫}: dấu chấm phân nhóm theo locale Đức (JDK không có locale vi-VN cho {@code %,d} ổn định). */
    static String vnd(long amount) {
        return String.format(Locale.GERMANY, "%,d ₫", amount);
    }
}
