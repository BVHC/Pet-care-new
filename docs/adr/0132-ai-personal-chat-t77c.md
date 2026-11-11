# ADR-0132: Chat cá nhân UC91 (T77c = AI-3, ai-v1 #2 và #3)

- **Trạng thái:** Proposed (Hiến làm việc AI-3 = T77c, 10/11/2026; chờ Hiến duyệt các điểm ở mục "Cần Hiến xác nhận").
- **Liên quan:** ADR-0006 (thú thuộc chủ hiện tại, người khác là 404), 0028 (Bean Validation chạy trước `@PreAuthorize`), 0082 (cửa sổ nhắc vaccine ST04), 0108 (`upcomingVaccinations`), 0122 (tên thú, từ thông dụng), 0129 (I-1…I-10 đã cài thật), 0131 (đường ống `ChatService`); đặc tả `07` §4.2 (BR-AI-11, 12b, 26, 27, 28), §5.2, §10, §11; hợp đồng `ai-v1` #2, #3.
- **Đánh số:** không có migration. ADR kế tiếp: **0133**.

## Bối cảnh

T77b cài `POST /api/public/ai/chat` và để sẵn chỗ cho `/me`: `PersonalIntentRouter` đã nhận `petNames` (và đã có so khớp tên thú, tên trùng từ thông dụng chỉ tính sau bé / con / chú / em), nên T77c không đổi chữ ký của router. T77c thêm `POST /api/me/ai/chat` (#2), `GET /api/me/ai/pets/{petId}/vaccination-summary` (#3), `PersonalService` (lịch hẹn sắp tới, lịch tiêm), `VaccinationSummaryService` và che tên thú (BR-AI-12b). Cả bốn interface đọc cần dùng (I-1, I-2, I-3 `listActiveProtocols`, I-10) đã có bản thật từ AI-0, nên mọi test chạy với interface thật; không có dòng "chưa chạy với interface thật".

## Quyết định

### D1. Luồng `/me` (07 §5.2 bước 1–6)
`ChatService.askAsCustomer(accountId, request)` dùng lại đúng đường ống của endpoint công khai, khác ở ba điểm:
1. **Khách lấy từ tài khoản của token** (`CustomerQueryApi.findCustomerIdByAccountId`), không bao giờ từ câu hỏi hay LLM (BR-AI-27). Tra khách và các thú còn sống (`PetQueryApi.findLivingPetsOfCustomer`, I-1) **sau khi kiểm đầu vào** (request sai là 400, không tra gì) và **trước cấp cứu**: câu hỏi bệnh án cần tên thú (Q43 "Đơn thuốc của Mochi…" không có "của tôi"), và nhật ký cần tên thú để che.
2. **Ý định cá nhân → `PersonalService`** (câu mẫu, không nhúng, không LLM, nhật ký chỉ ghi kết quả và độ trễ, BR-AI-24): `PERSONAL` hoặc `NEED_PET_CHOICE`.
3. **Che tên thú** thành `[thú]` trong câu hỏi và các câu hỏi trước, **sau** định tuyến (BR-AI-26 cần tên thú để nhận ý định) và **trước** nhúng và LLM (BR-AI-12b).

### D2. Một nguồn sự thật cho "nhắc tên thú" và "che tên thú"
`PhraseMatcher.spans` trả vị trí các lần khớp; `hasPhrase`, `PersonalIntentRouter.mentionedPetNames` (nhận ý định) và `PersonalIntentRouter.maskPetNames` (che) đều dựa vào đó, nên thứ router coi là "đã nhắc" thì cũng bị che, không sót và không che thừa. Hệ quả cụ thể: dạng bỏ dấu của tên từ 2 âm tiết trở lên ("bong gon") bị che như dạng có dấu; tên trùng từ thông dụng chỉ bị che sau bé / con / chú / em và **giữ tiền tố** ("Con Vàng" thành "Con [thú]", "giá vàng hôm nay" giữ nguyên); nhiều khoảng trắng giữa tiền tố và tên vẫn khớp; tên dài được che trước tên ngắn nằm trong nó ("Mi Mi" trước "Mi"); tên rỗng hoặc toàn khoảng trắng bị bỏ qua (trước đây `hasPhrase` với cụm rỗng khớp mọi ranh giới). Vị trí tính trên dạng NFC, chữ thường theo từng điểm mã và bỏ dấu theo từng ký tự (số ký tự không đổi, nên chỉ số khớp với câu gốc).

### D3. Tra lịch tiêm (BR-AI-11), `VaccinationSummaryService`
- Với mỗi loại vaccine có trong `listActiveProtocols(loài)` (I-3, theo loại vaccine rồi mũi): mũi **mới nhất chưa bị thay thế** của loại đó trong `findDoseHistory` (I-2): ngày tiêm lớn hơn thắng, cùng ngày thì mũi ghi sau (id lớn hơn); không dựa vào thứ tự trả về của I-2.
- `NOT_YET_ELIGIBLE` / `NOT_STARTED` (chưa có mũi nào): tuổi tính bằng tuần tròn từ ngày sinh đến hôm nay so với `min_age_weeks` của dòng mũi có số nhỏ nhất của loại đó; `firstDoseFromAgeWeeks` có ở hai trạng thái này.
- Có mũi: `OVERDUE` nếu `nextDueDate` < hôm nay; `DUE_SOON` nếu hôm nay ≤ `nextDueDate` ≤ hôm nay + N; còn lại `UP_TO_DATE`. **N đọc từ [CFG] `reminder.vaccine_days_before` qua `SystemConfigApi` ở mỗi lần gọi**; hai cận đều tính, cùng định nghĩa với `DueDateWindow` của ST04 (ADR-0082) để chatbot và nhắc lịch không lệch nhau một ngày. `DueDateWindow` nằm ở `visit.service` nên không dùng lại trực tiếp (modular monolith); chỉ lặp lại hai phép so sánh.
- `message` sinh từ mẫu cố định, mỗi vaccine một dòng; không qua LLM.

### D4. Lịch hẹn sắp tới (BR-AI-27), `PersonalService`
I-10 đã lọc `(slotDate, slotStart) >= now` trong SQL (`AppointmentAiReadIT`). Đặc tả vẫn đòi lọc lại ở module `ai` (lịch `BOOKED` đã quá giờ mà ST05 chưa chuyển `NO_SHOW` nằm trong dữ liệu), nên `PersonalService` lọc lại, **chỉ giữ lịch của thú còn sống của khách** (BR-AI-27: "mọi thú còn sống"), sắp lại theo thời gian và cắt theo [CFG] `ai.personal.appointments_limit` đọc mỗi lần gọi. Tên thú lấy từ danh sách thú còn sống đã có (không cần `findPets`), dịch vụ từ `CatalogQueryApi.findServices` (một lần theo lô), chi nhánh từ `BranchQueryApi.findBranch`; tên không tra được thì dùng "Dịch vụ #id" / "Chi nhánh #id", không lỗi.

### D5. Endpoint #3: thứ tự kiểm
Khách từ token → `PetQueryApi.findPet` → **kiểm chủ trước** (không có thú, thú của người khác, của chủ cũ, hay tài khoản chưa có hồ sơ khách đều là 404 cùng một message "Không tìm thấy thú cưng #id", ADR-0006) → **rồi mới kiểm đã mất** (400 `BR-KH-05`). Nên thú đã mất của người khác vẫn là 404, không lộ sự tồn tại. Quyền sở hữu kiểm lại **mỗi lần gọi**, không cache, vì `ai` không nghe `PetOwnerTransferredEvent`; có IT đổi chủ giữa hai lần gọi cùng một token. Bean Validation (kiểu của `petId`) chạy trước `@PreAuthorize` (ADR-0028), như các controller khác.

### D6. Nhật ký ở `/me`
`PERSONAL` / `NEED_PET_CHOICE` chỉ ghi kết quả và độ trễ (không ghi câu hỏi, câu trả lời). Ở mọi nhánh còn lại có ghi nội dung (cấp cứu, từ chối bệnh án, RAG), nhật ký ghi **bản đã che cả tên thú**, không chỉ che PII (xem giả định 2).

## Giả định ở chỗ đặc tả im lặng (không quyết ngầm: cần Hiến xác nhận ở mục cuối)

1. **Tài khoản chưa liên kết hồ sơ khách** (BR-TK-19; `findCustomerIdByAccountId` rỗng) **coi như không có thú và không có lịch** (đề xuất sẵn trong prompt): `/me/ai/chat` trả `PERSONAL` với "Bạn chưa có lịch hẹn sắp tới." (`bookingHint = true`) hoặc câu mẫu thêm thú; endpoint #3 trả 404 cho mọi `petId`. Không dùng 400 `BR-TK-19` như `/me/pets` vì hợp đồng #3 không liệt kê mã đó.
2. **Nhật ký ghi bản đã che tên thú ở mọi nhánh có ghi nội dung** (BR-AI-12b chỉ nói che "trước khi nhúng và gọi LLM"; BR-AI-24 nói nhật ký che dữ liệu nhưng không nêu tên thú). Chọn bảo thủ: "Mochi bị co giật" vào nhật ký là "[thú] bị co giật". Không ảnh hưởng định tuyến (dùng bản chưa che tên thú). Đổi lại thì sửa một dòng ở `ChatService.run`.
3. **Nhiều thú được nêu tên, hoặc hai thú trùng tên**: hỏi lại bằng `NEED_PET_CHOICE`, danh sách chỉ gồm các thú đã khớp (không phải toàn bộ thú của khách). Đặc tả chỉ nói "thú nêu tên thì dùng thú đó".
4. **Loại vaccine**: chỉ xét loại có trong `listActiveProtocols(loài)`; mũi của loại ngoài phác đồ bị bỏ qua. Thiếu ngày sinh thì không đánh giá tuổi (ra `NOT_STARTED`, không `NOT_YET_ELIGIBLE`). `nextDueDate` null thì `UP_TO_DATE` (cột `vaccinations.next_due_date` là `NOT NULL`, nên chỉ là phòng thủ). `nextDoseNumber` = số mũi sau chỉ khi phác đồ có dòng đó, ngược lại null (mũi nhắc lại hằng năm không có số).
5. **Lịch hẹn lọc sau `limit`**: I-10 nhận `limit` rồi `ai` mới lọc thú còn sống, nên có thể trả ít dòng hơn giới hạn dù còn lịch. Chấp nhận vì lịch `BOOKED` của thú đã mất / đã chuyển chủ hiếm; muốn tránh cần thêm bộ lọc thú vào I-10 (việc của BE-1).
6. **Câu mẫu**: đặc tả không nêu nguyên văn câu "chưa có thú cưng" (`ChatTexts.NO_PET`) và mẫu `VaccinationSummary.message`: chữ do ADR này chọn. "Bạn muốn xem lịch tiêm của bé nào?" và dòng lịch hẹn lấy nguyên văn ví dụ ở `07` §10; "Bạn chưa có lịch hẹn sắp tới." là câu của BR-AI-27 thêm dấu chấm (như `NEED_LOGIN` ở ADR-0131). Loài chưa có phác đồ đang dùng: "Chưa có phác đồ tiêm cho loài của {tên} trong danh mục." với `items` rỗng.
7. **#3 dùng `PetQueryApi.findPet`** (đủ tên, loài, ngày sinh, chủ, ngày mất trong một lần gọi) thay vì `findPetProfile` + `findPets` của I-1 (hai lần gọi, và `PetProfile` không có tên).
8. **Ghim parity**: ghim `hasSize` của `OpenApiContractParityIT.documentedModulesCoverAllTheirImplementedEndpoints` nâng từ 195 lên 197 (đúng 2 endpoint). Giá trị thật đọc được là 196 vì lệch `feedbacks` sẵn có (ca này đã đỏ ở mốc trước với 194 so với 195), không phải do T77c.

## Đột biến (test phải đỏ)

15 đột biến, mỗi lần chỉ sửa một chỗ trong mã, chạy 192 ca unit của 7 lớp (`PersonalServiceTest`, `VaccinationSummaryServiceTest`, `ChatServiceMeTest`, `ChatServiceTest`, `PetNameMaskingTest`, `PersonalIntentRouterTest`, `PhraseMatcherTest`) và, với M2, M14, M15, cả 18 ca `MeChatIT` (HTTP, Postgres thật), rồi khôi phục (script ở phiên làm việc, không nằm trong repo; mã khôi phục được kiểm bằng SHA-256 trước và sau, đủ 15 lần). **Cả 15 đều làm ít nhất một test đỏ**, không đột biến nào gây lỗi biên dịch. Bốn đột biến bắt buộc của prompt là M1, M2, M3, M4.

| # | Đột biến | Đỏ / số ca chạy | Test đỏ |
|---|---|---|---|
| M1 | Bỏ lọc `now` của lịch hẹn ở module `ai` (`PersonalService`) | 1 / 192 | `PersonalServiceTest.anAppointmentAlreadyPastIsFilteredAgainEvenIfTheInterfaceReturnsIt`. Chỉ unit bắt được: I-10 đã lọc `>= now` trong SQL nên IT không tạo được tình huống |
| M2 | Bỏ kiểm chủ hiện tại ở endpoint #3 (`VaccinationSummaryService`) | unit 4 / 192; IT 5 / 18 | unit: `aPetOfAnotherCustomerOrAFormerOwnerIsNotFoundWithTheSameMessage`, `aDeceasedPetOfSomeoneElseIsStillNotFoundNotARuleViolation`, `anAccountWithoutACustomerProfileOwnsNothing`, `theAccountIsResolvedToTheCustomerOnEveryCall`; IT: `aPetOfAnotherCustomerIsNotFoundWithTheSameMessageAsAnUnknownPet`, `aPetThatChangedOwnerIsNotFoundForTheFormerOwnerAtTheVeryNextCall`, `theOwnerIsCheckedOnEveryCallSoNothingIsCached`, `aDeceasedPetIs400ForItsOwnerAndStill404ForAnyoneElse`, `anAccountWithoutACustomerProfileGets404ForAnyPet` |
| M3 | Che tên thú TRƯỚC định tuyến (`router.detect` nhận câu đã che) | 5 / 192 | `ChatServiceMeTest.maskingRunsAfterRoutingSoANameAfterAPrefixStillGivesTheIntent`, `aNamedPetResolvesStraightToItsVaccinationSummary`, `personalResultsLogOnlyTheResultAndTheLatencyNeverTheContent`, và hai hàng của bảng `questions.json` (Q32 "Mochi tiêm khi nào?", Q40 "Con Vàng tiêm khi nào?") |
| M4 | Cố định ngưỡng `DUE_SOON` = 7 ngày thay vì đọc [CFG] | 1 / 192 | `VaccinationSummaryServiceTest.theDueSoonWindowIsReadFromTheConfigOnEveryCall` (N = 3, 5, 10 và `verify` đọc cấu hình đúng 3 lần cho 3 lần gọi) |
| M5 | Lịch hẹn của thú không còn sống / không còn của khách vẫn hiện | 1 / 192 | `PersonalServiceTest.anAppointmentOfAPetThatIsNoLongerAliveOrNoLongerTheirsIsNotShown` |
| M6 | Kiểm "đã mất" trước kiểm chủ (thú mất của người khác thành 400) | 1 / 192 | `VaccinationSummaryServiceTest.aDeceasedPetOfSomeoneElseIsStillNotFoundNotARuleViolation` |
| M7 | Nhật ký ghi câu hỏi chưa che tên thú | 3 / 192 | `ChatServiceMeTest.aMedicalRecordQuestionWithOnlyAPetNameIsRefusedAndLoggedWithoutTheName`, `anEmergencyIsLoggedWithoutThePetNameAndStillSkipsEmbeddingAndTheLlm`, `theLoggedQuestionOfANonPersonalTurnHasNoPetNameEither` |
| M8 | Bỏ khớp dạng bỏ dấu của tên thú từ 2 âm tiết (`PhraseMatcher.spans`) | 2 / 192 | `PhraseMatcherTest.unaccentedFormOnlyMatchesPhrasesOfTwoOrMoreSyllables`, `PetNameMaskingTest` hàng "bong gon ăn gì" |
| M9 | Ghi nội dung câu trả lời cá nhân (và `NEED_LOGIN`) vào nhật ký | 2 / 192 | `ChatServiceMeTest.personalResultsLogOnlyTheResultAndTheLatencyNeverTheContent`, `ChatServiceTest.aNeedLoginTurnLogsOnlyTheResultAndTheLatency` |
| M10 | Thú nêu tên không được ưu tiên (nhiều thú thì luôn hỏi lại) | 6 / 192 | `PersonalServiceTest.aNamedPetIsUsedEvenWhenTheCustomerHasSeveral`, `twoNamedPetsAreAmbiguousAndOnlyThoseTwoAreOffered`, `twoPetsWithTheSameNameAreAskedBackBecauseTheNameDoesNotIdentifyOne`; `ChatServiceMeTest.aNamedPetResolvesStraightToItsVaccinationSummary`, `personalResultsLogOnlyTheResultAndTheLatencyNeverTheContent`, hàng Q32 |
| M11 | Không bỏ mũi đã bị thay thế | 1 / 192 | `VaccinationSummaryServiceTest.aSupersededDoseIsIgnoredEvenWhenItIsTheNewest` |
| M12 | Biên trên cửa sổ `DUE_SOON` (hôm nay + N) không tính | 2 / 192 | `VaccinationSummaryServiceTest.theDueSoonWindowIsReadFromTheConfigOnEveryCall`, `theStatusFollowsTheNextDueDateWithBothBoundsInclusive` hàng "hạn sau 7 ngày" |
| M13 | Hạn đúng hôm nay bị tính là `OVERDUE` | 1 / 192 | `VaccinationSummaryServiceTest.theStatusFollowsTheNextDueDateWithBothBoundsInclusive` hàng "hạn hôm nay" |
| M14 | Bỏ `@PreAuthorize` CUSTOMER của endpoint #3 | IT 1 / 18 | `MeChatIT.theEndpointsNeedACustomerToken` (ADMIN phải 403). Chỉ IT bắt được: unit không dựng lớp web |
| M15 | Không che tên thú trong các câu hỏi trước | unit 1 / 192; **IT 0 / 18** | `ChatServiceMeTest.petNamesAreMaskedInTheQuestionAndInThePreviousQuestionsBeforeEmbeddingAndTheLlm`. IT không bắt: KB trống nên câu hỏi rơi xuống `OUT_OF_SCOPE` trước bước LLM, mà các câu hỏi trước chỉ đi vào khối dữ liệu của LLM; chỉ unit (lớp LLM giả ghi lại tham số) kiểm được |

## Kết quả kiểm thử (10/11/2026, cây `dev` kèm T77c)

- **Unit toàn dự án: 5985 xanh, 0 đỏ** (T77b ghi 5901; thêm 84 ca, đều ở module `ai`). Sau đó thêm một ca cuối (`ChatServiceMeTest.thePublicEndpointKnowsNoCustomerSoItNeverLooksUpPetsAndMasksNoPetName`, ở endpoint công khai không che tên thú, `07` §14.3) và chạy lại riêng unit ai: **307 xanh** (222 ở mốc trước): `PersonalServiceTest` 13, `VaccinationSummaryServiceTest` 27, `ChatServiceMeTest` 25 (gồm 11 hàng của bảng `questions.json` nhóm `me`), `PetNameMaskingTest` 20. Chưa chạy lại toàn dự án sau ca cuối.
- **IT nhóm ai: 114 xanh** (mốc trước 96): `MeChatIT` mới 18 ca (HTTP, Postgres + pgvector thật, era 2043, embedding / LLM giả), `ChatIT` 22 (hồi quy: không đổi), `AiDocumentFsmIT` 33, `KnowledgeIndexerIT` 20, `CatalogProtocolSyncIT` 7, `KnowledgeIndexTriggerIT` 6, `ChatLogCleanupIT` 5, `AiJobScheduleIT` 3.
- **`OpenApiContractParityIT`: 11 ca, 5 đỏ, đúng 5 ca đỏ của mốc trước** (không ca nào của `ai`): bốn ca do lệch `feedbacks` sẵn có (`POST /api/feedbacks/{id}/resolve` có trong code mà `content-v1.yaml` không có; mô tả, tham số, cây thuộc tính của `/api/me/feedbacks`, `/api/feedbacks`), và ca đếm endpoint: **196 so với ghim 197** (mốc trước 194 so với 195; chênh 1 cũng do `resolveFeedback`). Con số 196 = 194 + 2 chứng tỏ #2, #3 được tính đúng. Lần chạy đầu có một ca đỏ mới của T77c (`everyLeafPropertyHasARealisticExample`: `VaccinationSummary.message` thiếu `example`), đã sửa.
- Hợp đồng HTTP không đổi: `m_ai.py` và `ai-v1.yaml` đã có sẵn #2, #3; không chạy lại bộ sinh.

## Cần Hiến xác nhận

- Giả định 2 (nhật ký ghi bản che tên thú ở cả cấp cứu và từ chối bệnh án) và giả định 3 (hỏi lại chỉ trong các thú đã nêu tên).
- Giả định 1 cho tài khoản chưa có hồ sơ khách (404 ở #3, câu mẫu ở `/me`).
- Chữ của câu mẫu `NO_PET` và mẫu `VaccinationSummary.message` (giả định 6).

## Chưa làm / chưa chạy

- Chưa chạy qua BE thật (8081 / 8082) với Gemini thật: nhóm câu `/me` của bộ 43 câu mới chạy với lớp giả (CI không gọi mạng); p95 của nhóm này chưa đo. **T77 chưa DONE**: còn thiếu p95 đạt ở BE thật và quyết định về các điểm trên. Người rà soát tri thức vẫn là giả định cho demo (BS. Nguyễn Văn Nam), chưa có bác sĩ thú y thật (Q2 chưa giải).
- Chưa có gợi ý UC92, mở rộng ý định sang lưu trú / tái khám (`07` Q7), trường mới cho `BookedSlot` (ngoài phạm vi, theo prompt).
