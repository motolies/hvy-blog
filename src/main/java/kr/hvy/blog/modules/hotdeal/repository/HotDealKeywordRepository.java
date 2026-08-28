package kr.hvy.blog.modules.hotdeal.repository;

import java.util.List;
import kr.hvy.blog.modules.hotdeal.domain.entity.HotDealKeyword;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HotDealKeywordRepository extends JpaRepository<HotDealKeyword, Long> {

  /**
   * 스크래핑 실행 시 1회 로드용 (활성 키워드만).
   */
  List<HotDealKeyword> findByEnabledTrueOrderByKeywordAsc();

  /**
   * 관리 화면 목록용 (비활성 포함).
   */
  List<HotDealKeyword> findAllByOrderByKeywordAsc();

  /**
   * 관리자 대시보드 파이프라인 위젯용 — 활성 키워드 수.
   */
  long countByEnabledTrue();

  boolean existsByNormalizedKeyword(String normalizedKeyword);

  /**
   * 수정 시 중복 검사용. 자기 자신은 검사 대상에서 제외한다.
   */
  boolean existsByNormalizedKeywordAndIdNot(String normalizedKeyword, Long id);
}
