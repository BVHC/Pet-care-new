package com.petcare.module.returns;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.module.payment.dto.ResolveReconciliationItemRequest;
import com.petcare.module.returns.dto.ConfirmRefundRequest;
import com.petcare.module.returns.dto.CreateReturnRequest;
import com.petcare.module.returns.dto.RejectReturnRequest;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

class ReturnPaymentTextValidationTest {

    @Test
    void detectsEscapedNulAfterJsonDeserializationInEveryFreeTextField() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<Object> requests = List.of(
                json.readValue("""
                        {"orderId":1,"reasonNote":"a\\u0000b"}
                        """, CreateReturnRequest.class),
                json.readValue("""
                        {"reason":"a\\u0000b"}
                        """, RejectReturnRequest.class),
                json.readValue("""
                        {"note":"a\\u0000b"}
                        """, ConfirmRefundRequest.class),
                json.readValue("""
                        {"note":"a\\u0000b"}
                        """, ResolveReconciliationItemRequest.class));
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            for (Object request : requests) {
                assertThat(factory.getValidator().validate(request)).singleElement().satisfies(violation ->
                        assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(Pattern.class));
            }
        }
    }

    @Test
    void preservesMultilineTextAndEachExistingBlankPolicy() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String text : new String[] {null, "", "Dòng một\nDòng hai"}) {
                assertThat(validator.validate(new CreateReturnRequest(1L, null, null, text))).isEmpty();
                assertThat(validator.validate(new RejectReturnRequest(text))).isEmpty();
                assertThat(validator.validate(new ConfirmRefundRequest(text))).isEmpty();
            }
            assertThat(validator.validate(new ResolveReconciliationItemRequest("Dòng một\nDòng hai"))).isEmpty();
            assertThat(validator.validate(new ResolveReconciliationItemRequest(""))).singleElement()
                    .satisfies(violation -> assertThat(violation.getConstraintDescriptor().getAnnotation())
                            .isInstanceOf(NotBlank.class));
        }
    }
}
