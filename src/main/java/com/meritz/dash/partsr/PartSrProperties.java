package com.meritz.dash.partsr;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.partsr")
public record PartSrProperties(String outsourcingDeptCd) {
    public PartSrProperties {
        // 외주 부서코드는 application.yml(app.partsr.outsourcing-dept-cd)이 유일한 출처.
        // 설정 누락 시 조용히 기본값으로 오분류하지 않고 기동 단계에서 명시적으로 실패시킨다.
        if (outsourcingDeptCd == null || outsourcingDeptCd.isBlank()) {
            throw new IllegalStateException("app.partsr.outsourcing-dept-cd 설정이 필요합니다.");
        }
    }
}
