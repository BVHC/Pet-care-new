package com.petcare.module.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.petcare.TestcontainersConfiguration;
import com.petcare.module.customer.api.CustomerApi;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.OnlineProfileLinkability;
import com.petcare.module.customer.api.PetApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.customer.api.RecordedWeight;
import com.petcare.module.customer.api.WeightSource;
import com.petcare.platform.exception.BusinessRuleViolationException;

/**
 * {@link CustomerApi}, {@link CustomerQueryApi}, {@link PetApi}, {@link PetQueryApi} thật trên Postgres 17 (trả nợ D001,
 * D010; docs/adr/0041): đăng ký → cờ liên kết → từ chối / liên kết, ST02 xóa hồ sơ kèm sổ địa chỉ, cân nặng, khóa chia sẻ
 * dòng thú. Các lệnh ghi chạy trong {@link TransactionTemplate} như identity gọi.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CustomerModuleApiIT extends CustomerMeItSupport {

    @Autowired
    private CustomerApi customerApi;
    @Autowired
    private CustomerQueryApi customerQuery;
    @Autowired
    private PetApi petApi;
    @Autowired
    private PetQueryApi petQuery;

    private PetFixtures fx;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        fx = new PetFixtures(jdbc, transactionManager);
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        fx.cleanup();
    }

    // ---------------------------------------------------------------- CustomerApi

    @Test
    void writesRequireTheCallersTransaction() {
        assertThatThrownBy(() -> customerApi.createOnlineProfile(1L, "A", null))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> customerApi.flagLinkDecisionIfPhoneMatches(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> customerApi.deleteOnlineProfileOfUnverifiedAccount(1L))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> customerApi.linkAccountToCounterProfile(1L, 2L, "a@x.vn", "b@x.vn"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> customerApi.declineLink(1L)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void registrationCreatesAnOnlineProfileAndFlagsItWhenAnUnlinkedCounterProfileSharesThePhone() {
        String phone = "09" + (10_000_000 + (int) (Math.random() * 89_999_999));
        long counter = fx.track(counterProfile(null, "Khách quầy", phone, "quay@petcare.test"));
        long account = customerAccount(newEmail());

        Long id = tx.execute(s -> customerApi.createOnlineProfile(account, "Khách mạng", phone));
        fx.track(id);
        Boolean flagged = tx.execute(s -> customerApi.flagLinkDecisionIfPhoneMatches(account));

        assertThat(flagged).isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM customers WHERE id = ?", id))
                .containsEntry("created_channel", "ONLINE").containsEntry("account_id", account)
                .containsEntry("phone", phone).containsEntry("email", null)
                .containsEntry("link_decision_pending", true);
        assertThat(customerQuery.findCustomerIdByAccountId(account)).contains(id);
        assertThat(customerQuery.findContact(id)).get().satisfies(contact -> {
            assertThat(contact.linkDecisionPending()).isTrue();
            assertThat(contact.accountId()).isEqualTo(account);
        });
        assertThat(customerQuery.findLinkCandidates(phone)).singleElement().satisfies(candidate -> {
            assertThat(candidate.customerId()).isEqualTo(counter);
            assertThat(candidate.maskedFullName()).isEqualTo("Kh*** q");
            assertThat(candidate.hasEmail()).isTrue();
        });
    }

    @Test
    void noFlagWhenNoCounterProfileSharesThePhoneOrPhoneIsMissing() {
        long account = customerAccount(newEmail());
        long noPhoneAccount = customerAccount(newEmail());
        fx.track(tx.execute(s -> customerApi.createOnlineProfile(account, "A", "0988776655")));
        fx.track(tx.execute(s -> customerApi.createOnlineProfile(noPhoneAccount, "B", null)));

        Boolean withPhone = tx.execute(s -> customerApi.flagLinkDecisionIfPhoneMatches(account));
        Boolean withoutPhone = tx.execute(s -> customerApi.flagLinkDecisionIfPhoneMatches(noPhoneAccount));
        assertThat(withPhone).isFalse();
        assertThat(withoutPhone).isFalse();
    }

    @Test
    void declineClearsTheFlag() {
        String phone = "0977" + (100_000 + (int) (Math.random() * 899_999));
        fx.track(counterProfile(null, "Quầy", phone, null));
        long account = customerAccount(newEmail());
        fx.track(tx.execute(s -> customerApi.createOnlineProfile(account, "Mạng", phone)));
        tx.execute(s -> customerApi.flagLinkDecisionIfPhoneMatches(account));

        tx.executeWithoutResult(s -> customerApi.declineLink(account));

        assertThat(jdbc.queryForObject("SELECT link_decision_pending FROM customers WHERE account_id = ?",
                Boolean.class, account)).isFalse();
    }

    @Test
    void unverifiedAccountCleanupDeletesAddressesThenProfile() {
        long account = customerAccount(newEmail());
        long id = tx.execute(s -> customerApi.createOnlineProfile(account, "Chưa xác thực", null));
        address(id, "Một", true);
        address(id, "Hai", false);

        tx.executeWithoutResult(s -> customerApi.deleteOnlineProfileOfUnverifiedAccount(account));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM addresses WHERE customer_id = ?", Long.class, id))
                .isZero();
        // tài khoản không có hồ sơ: không làm gì, không lỗi
        tx.executeWithoutResult(s -> customerApi.deleteOnlineProfileOfUnverifiedAccount(account));
    }

    @Test
    void linkMovesTheAccountToTheCounterProfileAndDropsTheOnlineProfileWithItsAddresses() {
        String phone = "0966" + (100_000 + (int) (Math.random() * 899_999));
        long counter = fx.track(counterProfile(null, "Khách cũ", phone, "cu@petcare.test"));
        long petOfCounter = fx.pet(counter, "Mực", "DOG");
        long account = customerAccount(newEmail());
        long online = tx.execute(s -> customerApi.createOnlineProfile(account, "Khách mạng", phone));
        address(online, "Địa chỉ online", true);
        assertThat(customerQuery.checkOnlineProfileLinkable(account)).isEqualTo(OnlineProfileLinkability.LINKABLE);

        tx.executeWithoutResult(s -> customerApi.linkAccountToCounterProfile(account, counter, "cu@petcare.test",
                "acc@petcare.test"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?", Long.class, online)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM addresses WHERE customer_id = ?", Long.class, online))
                .isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM customers WHERE id = ?", counter))
                .containsEntry("account_id", account).containsEntry("email", "acc@petcare.test")
                .containsEntry("phone", phone).containsEntry("created_channel", "COUNTER");
        assertThat(jdbc.queryForObject("SELECT customer_id FROM pets WHERE id = ?", Long.class, petOfCounter))
                .isEqualTo(counter);
        assertThat(customerQuery.checkOnlineProfileLinkable(account)).isEqualTo(OnlineProfileLinkability.ALREADY_LINKED);
        assertThat(customerQuery.findCustomerIdByAccountId(account)).contains(counter);
    }

    @Test
    void linkIsRefusedWhenTheOnlineProfileHasDataFromAnyModule() {
        String[] sources = {"pet", "appointment", "boarding", "order", "feedback"};
        for (String source : sources) {
            long account = customerAccount(newEmail());
            long online = fx.track(tx.execute(s -> customerApi.createOnlineProfile(account, "Mạng " + source, null)));
            long counter = fx.track(counterProfile(null, "Quầy " + source, "0955000111", "q@petcare.test"));
            plantData(source, online);

            assertThat(customerQuery.checkOnlineProfileLinkable(account)).as(source)
                    .isEqualTo(OnlineProfileLinkability.HAS_DATA);
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> customerApi.linkAccountToCounterProfile(account,
                    counter, "q@petcare.test", "acc@petcare.test")))
                    .as(source).isExactlyInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("BR-TK-19");

            assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?", Long.class, online))
                    .as("không xóa gì khi từ chối: " + source).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT account_id FROM customers WHERE id = ?", Long.class, counter))
                    .isNull();
        }
    }

    @Test
    void linkIsRefusedWhenTheCounterEmailChangedAfterTheCodeWasSent() {
        long account = customerAccount(newEmail());
        long online = fx.track(tx.execute(s -> customerApi.createOnlineProfile(account, "Mạng", null)));
        long counter = fx.track(counterProfile(null, "Quầy", "0955000222", "moi@petcare.test"));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> customerApi.linkAccountToCounterProfile(account,
                counter, "cu@petcare.test", "acc@petcare.test"))).isInstanceOf(BusinessRuleViolationException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?", Long.class, online)).isOne();
    }

    @Test
    void failureAfterTheDeleteRollsEverythingBack() {
        long account = customerAccount(newEmail());
        long online = fx.track(tx.execute(s -> customerApi.createOnlineProfile(account, "Mạng", null)));
        address(online, "Địa chỉ", true);
        long counter = fx.track(counterProfile(null, "Quầy", "0955000333", "q3@petcare.test"));

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            customerApi.linkAccountToCounterProfile(account, counter, "q3@petcare.test", "acc@petcare.test");
            throw new IllegalStateException("identity ghi audit lỗi");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers WHERE id = ?", Long.class, online)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM addresses WHERE customer_id = ?", Long.class, online))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT account_id FROM customers WHERE id = ?", Long.class, counter)).isNull();
    }

    // ---------------------------------------------------------------- PetApi, PetQueryApi

    @Test
    void recordWeightWritesTheLogAndTheCurrentWeightFollowsTheNewestMeasurement() {
        long customer = fx.track(counterProfile(null, "Khách cân", "0955000444", null));
        long pet = fx.pet(customer, "Mực", "DOG");
        assertThat(petQuery.findPet(pet)).get().extracting(PetSummary::currentWeightKg).isNull();

        Long first = tx.execute(s -> petApi.recordWeight(pet, new BigDecimal("8.5"), WeightSource.OWNER, null, null,
                null)).weightRecordId();
        long visit = fx.visit(customer, pet, "COMPLETED");
        long actor = staffAccount();
        RecordedWeight secondRecord = tx.execute(s -> petApi.recordWeight(pet, new BigDecimal("9.25"),
                WeightSource.VET, visit, null, actor));
        Long second = secondRecord.weightRecordId();

        assertThat(second).isGreaterThan(first);
        assertThat(secondRecord.weightKg()).isEqualByComparingTo("9.25");
        assertThat(jdbc.queryForObject("SELECT measured_at FROM weight_records WHERE id = ?",
                java.sql.Timestamp.class, second).toInstant()).isEqualTo(secondRecord.measuredAt());
        assertThat(jdbc.queryForMap("SELECT * FROM weight_records WHERE id = ?", second))
                .containsEntry("source", "VET").containsEntry("visit_id", visit).containsEntry("recorded_by", actor)
                .containsEntry("boarding_booking_id", null);
        assertThat(petQuery.findPet(pet)).get().satisfies(summary -> {
            assertThat(summary.currentWeightKg()).isEqualByComparingTo("9.25");
            assertThat(summary.customerId()).isEqualTo(customer);
            assertThat(summary.species()).isEqualTo(Species.DOG);
            assertThat(summary.deceased()).isFalse();
        });
    }

    @Test
    void recordWeightRejectsInvalidAndDeceasedAndWritesNothing() {
        long customer = fx.track(counterProfile(null, "Khách cân", "0955000555", null));
        long pet = fx.pet(customer, "Mực", "DOG");

        assertThatThrownBy(() -> tx.execute(s -> petApi.recordWeight(pet, BigDecimal.ZERO, WeightSource.OWNER, null,
                null, null))).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-04");
        jdbc.update("UPDATE pets SET deceased_on = current_date WHERE id = ?", pet);
        assertThatThrownBy(() -> tx.execute(s -> petApi.recordWeight(pet, BigDecimal.ONE, WeightSource.OWNER, null,
                null, null))).isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("BR-KH-05");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM weight_records WHERE pet_id = ?", Long.class, pet))
                .isZero();
        assertThat(petQuery.findPet(pet)).get().extracting(PetSummary::deceased).isEqualTo(true);
        assertThat(petQuery.findPet(-1L)).isEmpty();
    }

    @Test
    void findPetForShareWaitsForAWriterAndRequiresATransaction() throws Exception {
        long customer = fx.track(counterProfile(null, "Khách khóa", "0955000666", null));
        long pet = fx.pet(customer, "Mực", "DOG");
        assertThatThrownBy(() -> petQuery.findPetForShare(pet)).isInstanceOf(IllegalTransactionStateException.class);

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT id FROM pets WHERE id = ? FOR NO KEY UPDATE", Long.class, pet);
            held.countDown();
            await(release);
            jdbc.update("UPDATE pets SET deceased_on = current_date WHERE id = ?", pet);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Optional<PetSummary>> reader = CompletableFuture
                .supplyAsync(() -> tx.execute(s -> petQuery.findPetForShare(pet)));
        awaitBlockedOnPets();
        assertThat(reader).isNotDone();
        release.countDown();

        writer.get(30, TimeUnit.SECONDS);
        assertThat(reader.get(30, TimeUnit.SECONDS)).get().extracting(PetSummary::deceased)
                .as("bên đến sau thấy dữ liệu đã commit của bên đến trước").isEqualTo(true);
    }

    // ---------------------------------------------------------------- nội bộ

    private void plantData(String source, long customerId) {
        switch (source) {
            case "pet" -> fx.pet(customerId, "Thú", "DOG");
            case "appointment" -> fx.appointment(customerId, 999_999L, "BOOKED");
            case "boarding" -> fx.booking(customerId, 999_998L, "BOOKED", 5);
            case "order" -> fx.orderForVisit(customerId, fx.visit(customerId, 999_997L, "COMPLETED"), "CANCELLED");
            case "feedback" -> fx.feedback(customerId);
            default -> throw new IllegalArgumentException(source);
        }
    }

    private void awaitBlockedOnPets() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Long waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE '%from pets%for share%'
                    """, Long.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("không thấy câu FOR SHARE chờ khóa dòng pets");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
