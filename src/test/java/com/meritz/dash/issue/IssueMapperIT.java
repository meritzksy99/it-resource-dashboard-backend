package com.meritz.dash.issue;

import com.meritz.dash.mapper.app.IssueMapper;
import com.meritz.dash.support.AbstractOracleIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IssueMapper 통합 테스트 (Testcontainers Oracle, V020 마이그레이션 적용).
 * BLOB 왕복, status IN 필터, 이슈 삭제 시 댓글 cascade, IDENTITY PK 회수를 확인한다.
 */
class IssueMapperIT extends AbstractOracleIT {

    @Autowired IssueMapper mapper;
    @Autowired @Qualifier("appDataSource") DataSource appDs;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(appDs);
    }

    @AfterEach
    void cleanup() {
        JdbcTemplate j = jdbc();
        j.update("DELETE FROM DASH_ISSUE_CMT WHERE ISSUE_ID IN (SELECT ISSUE_ID FROM DASH_ISSUE WHERE SCRN_ID LIKE 'IT_ISSUE_%')");
        j.update("DELETE FROM DASH_ISSUE WHERE SCRN_ID LIKE 'IT_ISSUE_%'");
    }

    @Test
    @DisplayName("insert 후 selectById/selectImage — BLOB(byte[]) 왕복 조회 + IDENTITY PK 회수")
    void insert_and_select_roundtrip_with_blob() {
        byte[] png = {1, 2, 3, 4, 5};
        IssueRow row = new IssueRow("IT_ISSUE_1", "9001", "화면이 깨져요", "shot.png", "image/png",
                png, "OPEN", "MEDIUM", null, "9001");

        int affected = mapper.insert(row);

        assertThat(affected).isEqualTo(1);
        assertThat(row.getIssueId()).isGreaterThan(0L); // IDENTITY PK 회수

        IssueDetail found = mapper.selectById(row.getIssueId());
        assertThat(found).isNotNull();
        assertThat(found.screenId()).isEqualTo("IT_ISSUE_1");
        assertThat(found.hasImage()).isTrue();

        IssueMapper.ImageData image = mapper.selectImage(row.getIssueId());
        assertThat(image).isNotNull();
        assertThat(image.fileCtype()).isEqualTo("image/png");
        assertThat(image.fileData()).containsExactly(png);
    }

    @Test
    @DisplayName("insert 이미지 없음 — hasImage=false, selectImage=null")
    void insert_without_image() {
        IssueRow row = new IssueRow("IT_ISSUE_2", "9001", "버튼이 안 눌림", null, null,
                null, "OPEN", "MEDIUM", null, "9001");

        mapper.insert(row);

        IssueDetail found = mapper.selectById(row.getIssueId());
        assertThat(found.hasImage()).isFalse();
        assertThat(mapper.selectImage(row.getIssueId())).isNull();
    }

    @Test
    @DisplayName("selectList — status IN 필터")
    void select_list_status_in_filter() {
        IssueRow open = new IssueRow("IT_ISSUE_3", "9001", "열림건", null, null, null, "OPEN", "MEDIUM", null, "9001");
        IssueRow resolved = new IssueRow("IT_ISSUE_3", "9001", "해결건", null, null, null, "RESOLVED", "MEDIUM", "해결함", "9001");
        mapper.insert(open);
        mapper.insert(resolved);

        List<IssueItem> onlyOpen = mapper.selectList(List.of("OPEN"), "IT_ISSUE_3");
        assertThat(onlyOpen).extracting(IssueItem::issueId).containsExactly(open.getIssueId());

        List<IssueItem> both = mapper.selectList(List.of("OPEN", "RESOLVED"), "IT_ISSUE_3");
        assertThat(both).hasSize(2);
    }

    @Test
    @DisplayName("이슈 삭제 시 댓글 cascade — DASH_ISSUE_CMT 도 함께 삭제")
    void delete_issue_cascades_comments() {
        IssueRow issueRow = new IssueRow("IT_ISSUE_4", "9001", "댓글 달릴 이슈", null, null, null,
                "OPEN", "MEDIUM", null, "9001");
        mapper.insert(issueRow);
        long issueId = issueRow.getIssueId();

        IssueCommentRow c1 = new IssueCommentRow(issueId, "9002", "댓글1", "9002");
        IssueCommentRow c2 = new IssueCommentRow(issueId, "9003", "댓글2", "9003");
        mapper.insertComment(c1);
        mapper.insertComment(c2);
        assertThat(c1.getCmtId()).isGreaterThan(0L);
        assertThat(mapper.selectComments(issueId)).hasSize(2);

        mapper.delete(issueId);

        Integer remaining = jdbc().queryForObject(
                "SELECT COUNT(*) FROM DASH_ISSUE_CMT WHERE ISSUE_ID = ?", Integer.class, issueId);
        assertThat(remaining).isZero();
        assertThat(mapper.selectById(issueId)).isNull();
    }

    @Test
    @DisplayName("댓글 수정/삭제 — updateComment/deleteComment 단건 반영")
    void comment_update_and_delete() {
        IssueRow issueRow = new IssueRow("IT_ISSUE_5", "9001", "이슈", null, null, null,
                "OPEN", "MEDIUM", null, "9001");
        mapper.insert(issueRow);
        long issueId = issueRow.getIssueId();

        IssueCommentRow c = new IssueCommentRow(issueId, "9002", "원본 댓글", "9002");
        mapper.insertComment(c);

        IssueCommentRow updated = new IssueCommentRow(issueId, "9002", "수정된 댓글", "9002");
        updated.setCmtId(c.getCmtId());
        mapper.updateComment(updated);

        assertThat(mapper.selectComment(issueId, c.getCmtId()).cmtCntt()).isEqualTo("수정된 댓글");

        mapper.deleteComment(issueId, c.getCmtId());
        assertThat(mapper.selectComment(issueId, c.getCmtId())).isNull();
    }
}
