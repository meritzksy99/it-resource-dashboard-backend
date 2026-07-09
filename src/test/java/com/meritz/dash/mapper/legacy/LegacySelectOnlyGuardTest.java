package com.meritz.dash.mapper.legacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기간계 매퍼 XML 정적 가드 테스트.
 * mapper/legacy/*.xml 에 쓰기 SQL(INSERT/UPDATE/DELETE/MERGE/DDL)이 없음을 검증한다.
 */
class LegacySelectOnlyGuardTest {

    @Test
    @DisplayName("기간계 매퍼 XML에 쓰기 SQL(INSERT/UPDATE/DELETE/MERGE/DDL)이 없다")
    void legacy_is_select_only() throws Exception {
        Resource[] xmls = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/legacy/*.xml");

        assertThat(xmls)
                .as("mapper/legacy/*.xml 파일이 1개 이상 존재해야 한다")
                .isNotEmpty();

        for (Resource r : xmls) {
            String body = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .toUpperCase();
            assertThat(body)
                    .as("파일 %s 에 <insert 태그가 있어서는 안 됨", r.getFilename())
                    .doesNotContain("<INSERT");
            assertThat(body)
                    .as("파일 %s 에 <update 태그가 있어서는 안 됨", r.getFilename())
                    .doesNotContain("<UPDATE");
            assertThat(body)
                    .as("파일 %s 에 <delete 태그가 있어서는 안 됨", r.getFilename())
                    .doesNotContain("<DELETE");
            assertThat(body)
                    .as("파일 %s 에 MERGE 문이 있어서는 안 됨", r.getFilename())
                    .doesNotContain("MERGE ");
            assertThat(body)
                    .as("파일 %s 에 CREATE 문이 있어서는 안 됨", r.getFilename())
                    .doesNotContain("CREATE ");
            assertThat(body)
                    .as("파일 %s 에 DROP 문이 있어서는 안 됨", r.getFilename())
                    .doesNotContain("DROP ");
            assertThat(body)
                    .as("파일 %s 에 ALTER 문이 있어서는 안 됨", r.getFilename())
                    .doesNotContain("ALTER ");
        }
    }
}
