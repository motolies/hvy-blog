package kr.hvy.blog.modules.advisor.application.dto;

import java.util.List;
import kr.hvy.blog.modules.advisor.domain.model.AdviceHeader;
import kr.hvy.blog.modules.advisor.domain.model.CallScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.CandidateRow;
import kr.hvy.blog.modules.advisor.domain.model.CandidateScoreRow;
import kr.hvy.blog.modules.advisor.domain.model.IntradayCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.MorningCheckRow;
import kr.hvy.blog.modules.advisor.domain.model.PickNoteRow;
import kr.hvy.blog.modules.advisor.domain.model.PickRow;

/**
 * 판단 상세: 헤더 + 후보(동결 스냅샷) + 픽 + 채점 + 장중 점검 + 아침 점검(advice-v3, 없으면 null) + 12:00 픽 노트(note-v1, 없으면 빈 목록).
 */
public record AdviceDetailResponse(
    AdviceHeader header,
    List<CandidateRow> candidates,
    List<PickRow> picks,
    List<CandidateScoreRow> candidateScores,
    List<CallScoreRow> callScores,
    List<IntradayCheckRow> intradayChecks,
    MorningCheckRow morningCheck,
    List<PickNoteRow> notes) {
}
