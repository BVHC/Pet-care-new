package com.petcare.module.identity.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.identity.api.ConfigValueType;
import com.petcare.module.identity.dto.SystemConfigResponse;
import com.petcare.module.identity.entity.SystemConfig;

/**
 * identity-v1 schema {@code SystemConfig} (#31, #32; docs/adr/0036): mỗi trường lấy đúng nguồn — mỗi field của entity có
 * giá trị khác nhau nên map nhầm nguồn là đỏ; trường null vẫn là null.
 */
class SystemConfigAdminMapperTest {

    private final SystemConfigAdminMapper mapper = Mappers.getMapper(SystemConfigAdminMapper.class);

    @Test
    void everyFieldComesFromItsOwnColumn() {
        SystemConfig config = config("otp.ttl_minutes", "7", ConfigValueType.INT, "1", "15", "phút",
                "Thời gian hiệu lực của mã OTP (BR-TK-05)");
        ReflectionTestUtils.setField(config, "updatedBy", 42L);

        assertThat(mapper.toResponse(config)).isEqualTo(new SystemConfigResponse("otp.ttl_minutes", "7",
                ConfigValueType.INT, "1", "15", "phút", "Thời gian hiệu lực của mã OTP (BR-TK-05)"));
    }

    @Test
    void nullableFieldsStayNull() {
        SystemConfig config = config("x.flag", "true", ConfigValueType.BOOL, null, null, null, "Cờ");

        assertThat(mapper.toResponse(config))
                .isEqualTo(new SystemConfigResponse("x.flag", "true", ConfigValueType.BOOL, null, null, null, "Cờ"));
    }

    private static SystemConfig config(String key, String value, ConfigValueType type, String min, String max,
            String unit, String description) {
        SystemConfig config = BeanUtils.instantiateClass(SystemConfig.class);
        ReflectionTestUtils.setField(config, "configKey", key);
        ReflectionTestUtils.setField(config, "value", value);
        ReflectionTestUtils.setField(config, "valueType", type);
        ReflectionTestUtils.setField(config, "minValue", min);
        ReflectionTestUtils.setField(config, "maxValue", max);
        ReflectionTestUtils.setField(config, "unit", unit);
        ReflectionTestUtils.setField(config, "description", description);
        return config;
    }
}
