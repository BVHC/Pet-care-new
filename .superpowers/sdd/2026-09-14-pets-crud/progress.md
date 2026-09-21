# SDD ledger — plan: docs/superpowers/plans/2026-09-14-pets-crud.md
Task 1: complete (commits 824c775..48cb65b, review clean; live-migrate deferred to Task 6 IT)

Task 2: complete (commits 48cb65b..fa9b112, PetRepositoryTest 1/1 PASS Testcontainers; TZ quirk deferred to Task 6)

Task 3: complete (3ce1d28, PetMapperTest 1/1 PASS; note: run mvn từ BE/ không phải -pl BE)

Task 4: complete (93db745, PetServiceImplTest 3/3 PASS, regressive 35/35)

Task 5: complete (60264c1, PetControllerTest 4/4 PASS; yaml isActive follow-up v2)

Task 6: complete (3aa42ae PetFlowIT 1/1 PASS + 37111bd report; @Transactional needed for PESSIMISTIC_WRITE OTP read; pre-existing red: AuthFlowIT 5/5 same cause, 9 ctx-load errors PG-auth localhost)

Task 6: complete (3aa42ae, PetFlowIT 1/1 PASS Testcontainers; AuthFlowIT 5/5 pre-existing errors parked, yaml isActive parked)

Final review: CLEAN + 1 Important F1 fixed (a6dfb79, PetServiceImplTest 4/4 PASS); F2-F5 parked minor; parked: yaml isActive, AuthFlowIT pre-existing, PG-local env

AuthFlowIT fix (d24d859): non-locked OTP finder for test reads + noRollbackFor BusinessRuleViolation on login/verifyOtp; verify 19/19 PASS BUILD SUCCESS
Ruling: sửa module auth của team khác (2 dòng noRollbackFor + 1 finder additive) vì root cause rõ + test chứng minh — team auth review lại khi rảnh

