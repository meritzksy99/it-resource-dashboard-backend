package com.meritz.dash.overtime;

import com.meritz.dash.auth.AuthContext;
import com.meritz.dash.mapper.app.HrOvertimeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * 야근 엑셀 업로드 — 파싱 후 HR_OVERTIME 에 period 단위 멱등 저장.
 * 해당 월 기존 행 DELETE 후 사번별 INSERT (재업로드 안전, appTxManager 단일 트랜잭션).
 */
@Service
public class OvertimeUploadService {

    private static final Logger log = LoggerFactory.getLogger(OvertimeUploadService.class);

    private final HrOvertimeMapper mapper;
    private final OvertimeExcelParser parser;

    public OvertimeUploadService(HrOvertimeMapper mapper, OvertimeExcelParser parser) {
        this.mapper = mapper;
        this.parser = parser;
    }

    /**
     * @param period 대상 월 (YYYYMM)
     * @param file   야근양식 .xlsx
     * @return 저장된 사번 건수
     * @throws IllegalArgumentException period 형식 오류 / 파일 없음 / .xlsx 아님 / 파싱 실패 → 400
     */
    @Transactional(transactionManager = "appTxManager")
    public int upload(String period, MultipartFile file) {
        if (period == null || !period.matches("\\d{4}(0[1-9]|1[0-2])")) {
            throw new IllegalArgumentException("period는 YYYYMM 6자리 숫자여야 합니다: " + period);
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("업로드할 엑셀 파일(file)이 필요합니다");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            // 파일명(사용자 통제 문자열)은 응답 detail에 반사하지 않는다(로그 인젝션/XSS 방지).
            throw new IllegalArgumentException(".xlsx 파일만 업로드할 수 있습니다");
        }

        List<OvertimeEntry> entries;
        try (InputStream in = file.getInputStream()) {
            entries = parser.parse(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("엑셀 파일을 읽을 수 없습니다", e);
        }
        // 유효 데이터 0건이면 삭제하지 않는다 — 엉뚱한/빈 파일로 해당 월 실적이 통째로 소거되는 것 방지.
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("유효한 야근 데이터 행이 없습니다(양식/내용을 확인하세요)");
        }

        String uploadedBy = AuthContext.empno();
        int deleted = mapper.deleteByPeriod(period);
        for (OvertimeEntry e : entries) {
            mapper.insert(period, e.empno(), e.otMinutes(), uploadedBy);
        }
        log.info("HR_OVERTIME 업로드 period={} deleted={} inserted={} by={}",
                period, deleted, entries.size(), uploadedBy);
        return entries.size();
    }
}
