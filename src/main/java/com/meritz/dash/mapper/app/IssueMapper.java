package com.meritz.dash.mapper.app;

import com.meritz.dash.issue.IssueComment;
import com.meritz.dash.issue.IssueCommentRow;
import com.meritz.dash.issue.IssueDetail;
import com.meritz.dash.issue.IssueItem;
import com.meritz.dash.issue.IssueRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 이슈관리(DASH_ISSUE/DASH_ISSUE_CMT) 매퍼(app DB). BLOB(FILE_DATA)은 목록/상세 조회에는
 * 포함하지 않고 {@link #selectImage} 로만 회수한다.
 */
public interface IssueMapper {

    /** 목록. statuses(화이트리스트 검증은 서비스단) null 이면 전체, screenId 는 부분/정확 일치(구현시 결정). BLOB 미포함. */
    List<IssueItem> selectList(@Param("statuses") List<String> statuses, @Param("screenId") String screenId);

    /** 상세(BLOB 제외) + 댓글 수. 없으면 null. */
    IssueDetail selectById(@Param("issueId") Long issueId);

    /** 스크린샷 원본(BLOB) + Content-Type. 이슈 미존재 또는 이미지 없음이면 null. */
    ImageData selectImage(@Param("issueId") Long issueId);

    /** INSERT. useGeneratedKeys=true, keyProperty="issueId", keyColumn="ISSUE_ID". */
    int insert(IssueRow row);

    /** 부분수정 UPDATE(널 아닌 컬럼만 반영은 구현에서 결정). */
    int update(IssueRow row);

    /** 이슈 삭제(FK ON DELETE CASCADE 로 댓글도 함께 삭제). */
    int delete(@Param("issueId") Long issueId);

    /** 댓글 목록(등록순 오름차순). */
    List<IssueComment> selectComments(@Param("issueId") Long issueId);

    /** 댓글 단건(권한 판정용). 없으면 null. */
    IssueComment selectComment(@Param("issueId") Long issueId, @Param("commentId") Long commentId);

    /** 댓글 INSERT. useGeneratedKeys=true, keyProperty="cmtId", keyColumn="CMT_ID". */
    int insertComment(IssueCommentRow row);

    /** 댓글 UPDATE. */
    int updateComment(IssueCommentRow row);

    /** 댓글 삭제. */
    int deleteComment(@Param("issueId") Long issueId, @Param("commentId") Long commentId);

    /** 이미지 원본 + Content-Type. */
    record ImageData(byte[] fileData, String fileCtype) {}
}
