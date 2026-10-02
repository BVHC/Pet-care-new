package com.petcare.platform.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.petcare.platform.config.TraceContext;
import com.petcare.platform.config.TraceIdFilter;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Mỗi dòng của bảng mapping exception → errorCode → HTTP: đúng status, đủ 6 field,
 * traceId trong body trùng header {@code X-Trace-Id}.
 */
class GlobalExceptionHandlerTest {

    record PetRequest(@NotNull Long petId) {
    }

    @RestController
    @RequestMapping("/t")
    static class ThrowingController {

        private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

        @GetMapping("/rule")
        void rule() {
            throw new BusinessRuleViolationException("BR-LH-05", "Thú cưng đã có 2 lịch BOOKED");
        }

        @GetMapping("/transition")
        void transition() {
            throw new InvalidStateTransitionException("AppointmentTransitionHandler", "COMPLETED", "BOOKED");
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new ResourceNotFoundException("Appointment", 42L);
        }

        @GetMapping("/scope")
        void scope() {
            throw new AccessDeniedScopeException("BRANCH:1", "BRANCH:2");
        }

        @GetMapping("/concurrency")
        void concurrency() {
            throw new ConcurrencyConflictException("Appointment", 42L);
        }

        @PostMapping("/body")
        void body(@Valid @RequestBody PetRequest request) {
        }

        @GetMapping("/param")
        void param(@RequestParam @Min(1) int size) {
        }

        @GetMapping("/constraint")
        void constraint() {
            throw new ConstraintViolationException(VALIDATOR.validate(new PetRequest(null)));
        }

        @GetMapping("/typed/{id}")
        void typed(@PathVariable Long id) {
        }

        @GetMapping("/required")
        void required(@RequestParam String q) {
        }

        @GetMapping("/unauthenticated")
        void unauthenticated() {
            throw new InsufficientAuthenticationException("no token");
        }

        @GetMapping("/forbidden")
        void forbidden() {
            throw new AccessDeniedException("role VET required");
        }

        @GetMapping("/integrity")
        void integrity() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_pet_slot\"");
        }

        @GetMapping("/lock")
        void lock() {
            throw new PessimisticLockingFailureException("could not obtain lock");
        }

        @GetMapping("/status-404")
        void status404() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new TraceIdFilter())
            .build();

    @Test
    void businessRuleViolationEmbedsRuleId() throws Exception {
        expectError(mvc.perform(get("/t/rule")), ErrorCode.BUSINESS_RULE_VIOLATION)
                .andExpect(jsonPath("$.message").value("Thú cưng đã có 2 lịch BOOKED (BR-LH-05)"));
    }

    @Test
    void invalidStateTransitionIs409() throws Exception {
        expectError(mvc.perform(get("/t/transition")), ErrorCode.INVALID_STATE_TRANSITION)
                .andExpect(jsonPath("$.message", containsString("COMPLETED")));
    }

    @Test
    void resourceNotFoundIs404() throws Exception {
        expectError(mvc.perform(get("/t/not-found")), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void scopeMismatchHidesScopeDetails() throws Exception {
        expectError(mvc.perform(get("/t/scope")), ErrorCode.ACCESS_DENIED_SCOPE_MISMATCH)
                .andExpect(jsonPath("$.message", not(containsString("BRANCH"))));
    }

    @Test
    void concurrencyConflictIs409() throws Exception {
        expectError(mvc.perform(get("/t/concurrency")), ErrorCode.CONCURRENCY_CONFLICT);
    }

    @Test
    void bodyValidationListsFields() throws Exception {
        expectError(mvc.perform(post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{}")),
                ErrorCode.VALIDATION_FAILED)
                .andExpect(jsonPath("$.message", containsString("petId: ")));
    }

    @Test
    void methodParameterValidationListsParameter() throws Exception {
        expectError(mvc.perform(get("/t/param").param("size", "0")), ErrorCode.VALIDATION_FAILED)
                .andExpect(jsonPath("$.message", containsString("size: ")));
    }

    @Test
    void constraintViolationListsProperty() throws Exception {
        expectError(mvc.perform(get("/t/constraint")), ErrorCode.VALIDATION_FAILED)
                .andExpect(jsonPath("$.message", containsString("petId: ")));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        expectError(mvc.perform(post("/t/body").contentType(MediaType.APPLICATION_JSON).content("{petId:")),
                ErrorCode.MALFORMED_REQUEST);
    }

    @Test
    void typeMismatchIs400() throws Exception {
        expectError(mvc.perform(get("/t/typed/abc")), ErrorCode.MALFORMED_REQUEST)
                .andExpect(jsonPath("$.message", containsString("'id'")));
    }

    @Test
    void missingParameterIs400() throws Exception {
        expectError(mvc.perform(get("/t/required")), ErrorCode.MALFORMED_REQUEST)
                .andExpect(jsonPath("$.message", containsString("'q'")));
    }

    @Test
    void unknownPathIs404() throws Exception {
        expectError(mvc.perform(get("/t/does-not-exist")), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void wrongMethodIs405() throws Exception {
        expectError(mvc.perform(post("/t/rule")), ErrorCode.METHOD_NOT_ALLOWED);
    }

    @Test
    void unsupportedMediaTypeIs415() throws Exception {
        expectError(mvc.perform(post("/t/body").contentType(MediaType.TEXT_PLAIN).content("x")),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void authenticationExceptionIs401() throws Exception {
        expectError(mvc.perform(get("/t/unauthenticated")), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void accessDeniedIs403() throws Exception {
        expectError(mvc.perform(get("/t/forbidden")), ErrorCode.ACCESS_DENIED);
    }

    @Test
    void dataIntegrityViolationIs409WithoutConstraintName() throws Exception {
        expectError(mvc.perform(get("/t/integrity")), ErrorCode.CONCURRENCY_CONFLICT)
                .andExpect(jsonPath("$.message", not(containsString("uq_pet_slot"))));
    }

    @Test
    void lockFailureIs409() throws Exception {
        expectError(mvc.perform(get("/t/lock")), ErrorCode.CONCURRENCY_CONFLICT);
    }

    @Test
    void otherSpringClientErrorKeepsItsStatus() throws Exception {
        expectError(mvc.perform(get("/t/status-404")), ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void unexpectedErrorIs500WithoutDetails() throws Exception {
        expectError(mvc.perform(get("/t/boom")), ErrorCode.INTERNAL_ERROR)
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.INTERNAL_MESSAGE));
    }

    @Test
    void traceIdFromClientIsEchoedInBody() throws Exception {
        mvc.perform(get("/t/rule").header(TraceContext.HEADER, "demo-1"))
                .andExpect(header().string(TraceContext.HEADER, "demo-1"))
                .andExpect(jsonPath("$.traceId").value("demo-1"));
    }

    private static ResultActions expectError(ResultActions result, ErrorCode errorCode) throws Exception {
        String traceId = result.andReturn().getResponse().getHeader(TraceContext.HEADER);
        return result
                .andExpect(status().is(errorCode.status().value()))
                .andExpect(jsonPath("$.*", hasSize(6)))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(errorCode.name()))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.statusCode").value(errorCode.status().value()))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.traceId").value(traceId));
    }
}
