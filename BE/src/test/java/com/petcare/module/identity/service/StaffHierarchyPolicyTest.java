package com.petcare.module.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.exception.StaffHierarchyDeniedException;
import com.petcare.module.identity.service.StaffHierarchyPolicy.Actor;
import com.petcare.module.identity.service.StaffHierarchyPolicy.Request;

/**
 * Ma trận phân cấp (docs/adr/0029 mục 1). Bảng kỳ vọng dưới đây chép độc lập từ đặc tả, không gọi code của policy:
 * mọi tổ hợp role người thực hiện × role người bị tác động × chi nhánh (cùng / khác / không có) × thao tác được duyệt
 * hết, giống {@code FsmTransitionTestBase}.
 * <ul>
 *   <li>BR-QT-01 (02 L44): ADMIN tạo SUPER_MANAGER; SUPER_MANAGER tạo A05–A08; BRANCH_MANAGER tạo A06–A08 trong
 *       chi nhánh mình. 00-method L40: BRANCH_MANAGER không gửi chi nhánh = chi nhánh mình.</li>
 *   <li>BR-QT-05, 06 (L48, L49): chỉ SUPER_MANAGER đổi chức vụ (trong nhóm A05–A08), điều chuyển (A05–A08).</li>
 *   <li>BR-QT-07, 10 (L50, L53): ADMIN → SUPER_MANAGER; SUPER_MANAGER → A05–A08; BRANCH_MANAGER → A06–A08 chi
 *       nhánh mình; không ai tự vô hiệu hóa chính mình. Kích hoạt lại cùng phạm vi, BRANCH_MANAGER không gán sang
 *       chi nhánh khác.</li>
 *   <li>BR-QT-02, BR-TK-16 (L45, L32): gửi lại mật khẩu ban đầu, sửa hộ email "theo phân cấp tạo tài khoản";
 *       BR-TK-15 (L31): không tự sửa email của mình.</li>
 * </ul>
 */
class StaffHierarchyPolicyTest {

    private static final long OWN_BRANCH = 10L;
    private static final long OTHER_BRANCH = 20L;
    private static final long ACTOR_ID = 1L;
    private static final long TARGET_ID = 2L;
    private static final Long[] BRANCHES = {OWN_BRANCH, OTHER_BRANCH, null};

    private static final Set<Role> A05_A08 = EnumSet.of(Role.BRANCH_MANAGER, Role.RECEPTIONIST, Role.VET,
            Role.CARETAKER);
    private static final Set<Role> A06_A08 = EnumSet.of(Role.RECEPTIONIST, Role.VET, Role.CARETAKER);

    /** "Ai quản lý role nào" theo BR-QT-01, 07 — bản chép độc lập. */
    private static final Map<Role, Set<Role>> MANAGES = Map.of(
            Role.ADMIN, EnumSet.of(Role.SUPER_MANAGER),
            Role.SUPER_MANAGER, A05_A08,
            Role.BRANCH_MANAGER, A06_A08);

    private final StaffHierarchyPolicy policy = new StaffHierarchyPolicy();

    private static Actor actor(Role role) {
        Long branch = A05_A08.contains(role) ? OWN_BRANCH : null;
        return new Actor(ACTOR_ID, role, branch);
    }

    private static boolean manages(Role actor, Role target, Long targetBranch) {
        Set<Role> managed = MANAGES.getOrDefault(actor, Set.of());
        if (!managed.contains(target)) {
            return false;
        }
        return actor != Role.BRANCH_MANAGER || Long.valueOf(OWN_BRANCH).equals(targetBranch);
    }

    @TestFactory
    Stream<DynamicTest> createMatchesSpec() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Role actorRole : Role.values()) {
            for (Role requested : Role.values()) {
                for (Long branch : BRANCHES) {
                    Long effective = actorRole == Role.BRANCH_MANAGER && branch == null
                            ? Long.valueOf(OWN_BRANCH) : branch;
                    boolean expected = manages(actorRole, requested, effective);
                    tests.add(DynamicTest.dynamicTest(
                            actorRole + " CREATE " + requested + " branch=" + branch + " → " + expected,
                            () -> assertThat(policy.allows(actor(actorRole), Request.create(requested, branch)))
                                    .isEqualTo(expected)));
                }
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> disableResendAndEmailChangeMatchSpec() {
        List<DynamicTest> tests = new ArrayList<>();
        for (StaffAction action : List.of(StaffAction.DISABLE, StaffAction.RESEND_INITIAL_PASSWORD,
                StaffAction.CHANGE_EMAIL)) {
            for (Role actorRole : Role.values()) {
                for (Role target : Role.values()) {
                    for (Long branch : BRANCHES) {
                        boolean expected = manages(actorRole, target, branch);
                        tests.add(DynamicTest.dynamicTest(
                                actorRole + " " + action + " " + target + " branch=" + branch + " → " + expected,
                                () -> assertThat(policy.allows(actor(actorRole),
                                        Request.on(action, TARGET_ID, target, branch))).isEqualTo(expected)));
                    }
                }
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> reactivateMatchesSpec() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Role actorRole : Role.values()) {
            for (Role target : Role.values()) {
                for (Long branch : BRANCHES) {
                    for (Long requested : BRANCHES) {
                        boolean expected = manages(actorRole, target, branch)
                                && (actorRole != Role.BRANCH_MANAGER || requested == null
                                    || requested == OWN_BRANCH);
                        tests.add(DynamicTest.dynamicTest(actorRole + " REACTIVATE " + target + " branch=" + branch
                                        + " newBranch=" + requested + " → " + expected,
                                () -> assertThat(policy.allows(actor(actorRole),
                                        Request.reactivate(TARGET_ID, target, branch, requested)))
                                        .isEqualTo(expected)));
                    }
                }
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> changeRoleMatchesSpec() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Role actorRole : Role.values()) {
            for (Role target : Role.values()) {
                for (Role newRole : Role.values()) {
                    boolean expected = actorRole == Role.SUPER_MANAGER && A05_A08.contains(target)
                            && A05_A08.contains(newRole);
                    tests.add(DynamicTest.dynamicTest(
                            actorRole + " CHANGE_ROLE " + target + " → " + newRole + " → " + expected,
                            () -> assertThat(policy.allows(actor(actorRole),
                                    Request.changeRole(TARGET_ID, target, OWN_BRANCH, newRole)))
                                    .isEqualTo(expected)));
                }
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> transferMatchesSpec() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Role actorRole : Role.values()) {
            for (Role target : Role.values()) {
                for (Long branch : BRANCHES) {
                    boolean expected = actorRole == Role.SUPER_MANAGER && A05_A08.contains(target);
                    tests.add(DynamicTest.dynamicTest(
                            actorRole + " TRANSFER " + target + " from=" + branch + " → " + expected,
                            () -> assertThat(policy.allows(actor(actorRole),
                                    Request.transfer(TARGET_ID, target, branch, OTHER_BRANCH)))
                                    .isEqualTo(expected)));
                }
            }
        }
        return tests.stream();
    }

    /**
     * BR-QT-07 "không ai tự vô hiệu hóa chính mình"; BR-TK-15 email không tự sửa — mọi thao tác có người bị tác động.
     * Role của người bị tác động lấy từ <b>mọi</b> role mà người thực hiện quản lý được, không chỉ role của chính họ:
     * principal đọc ở đầu request, role dưới khóa có thể đã đổi (docs/adr/0029, khoảng hở mục H) — ví dụ SUPER_MANAGER
     * vừa bị đổi thành VET vẫn không được tự vô hiệu hóa. Với role trùng nhau, ma trận đã tự từ chối nên test đó không
     * phân biệt được có kiểm "chính mình" hay không.
     */
    @TestFactory
    Stream<DynamicTest> selfIsAlwaysDenied() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Role actorRole : List.of(Role.ADMIN, Role.SUPER_MANAGER, Role.BRANCH_MANAGER)) {
            Actor self = actor(actorRole);
            for (StaffAction action : StaffAction.values()) {
                if (action == StaffAction.CREATE) {
                    continue;
                }
                for (Role targetRole : Role.values()) {
                    Request request = new Request(action, ACTOR_ID, targetRole, self.branchId(), Role.VET, null);
                    tests.add(DynamicTest.dynamicTest(actorRole + " " + action + " self as " + targetRole,
                            () -> assertThat(policy.allows(self, request)).isFalse()));
                }
            }
        }
        return tests.stream();
    }

    @Test
    void branchManagerCreatesInOwnBranchWhenBranchOmitted() {
        assertThat(policy.allows(actor(Role.BRANCH_MANAGER), Request.create(Role.VET, null))).isTrue();
        assertThat(policy.allows(actor(Role.BRANCH_MANAGER), Request.create(Role.VET, OTHER_BRANCH))).isFalse();
    }

    @Test
    void branchManagerWithoutBranchManagesNobody() {
        Actor orphan = new Actor(ACTOR_ID, Role.BRANCH_MANAGER, null);

        assertThat(policy.allows(orphan, Request.create(Role.VET, null))).isFalse();
        assertThat(policy.allows(orphan, Request.on(StaffAction.DISABLE, TARGET_ID, Role.VET, null))).isFalse();
    }

    @Test
    void checkPassesWhenAllowed() {
        assertThatCode(() -> policy.check(actor(Role.SUPER_MANAGER), Request.create(Role.VET, OWN_BRANCH)))
                .doesNotThrowAnyException();
    }

    @Test
    void checkThrowsWithAuditEntryWhenDenied() {
        assertThatThrownBy(() -> policy.check(actor(Role.BRANCH_MANAGER),
                Request.on(StaffAction.DISABLE, TARGET_ID, Role.VET, OTHER_BRANCH)))
                .isInstanceOf(StaffHierarchyDeniedException.class)
                .satisfies(ex -> {
                    var entry = ((StaffHierarchyDeniedException) ex).auditEntry();
                    assertThat(entry.action()).isEqualTo(QtAuditActions.STAFF_ACTION_DENIED);
                    assertThat(entry.entityType()).isEqualTo("accounts");
                    assertThat(entry.entityId()).isEqualTo(TARGET_ID);
                });
    }

    @Test
    void requestRequiresTargetExceptForCreate() {
        assertThatThrownBy(() -> Request.on(StaffAction.DISABLE, null, Role.VET, OWN_BRANCH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Request.create(null, OWN_BRANCH)).isInstanceOf(NullPointerException.class);
    }

    /** docs/adr/0029 mục Transaction A: policy không có proxy transaction, nên ném lỗi không đánh dấu rollback-only. */
    @Test
    void notTransactional() {
        assertThat(StaffHierarchyPolicy.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(Arrays.stream(StaffHierarchyPolicy.class.getDeclaredMethods())
                .filter((Method m) -> m.isAnnotationPresent(Transactional.class))).isEmpty();
    }
}
