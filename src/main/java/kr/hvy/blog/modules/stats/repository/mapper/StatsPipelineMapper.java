package kr.hvy.blog.modules.stats.repository.mapper;

import java.time.Instant;
import java.util.List;
import kr.hvy.blog.modules.stats.application.dto.HotDealSiteStat;
import kr.hvy.blog.modules.stats.application.dto.JiraStat;
import kr.hvy.blog.modules.stats.application.dto.MemoStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 사이드 파이프라인 집계 — 핫딜 / 메모 / Jira. */
@Mapper
public interface StatsPipelineMapper {

  /** 사이트별 수집 현황. 수집이 0건인 사이트도 반환해야 하므로 LEFT JOIN 이다. */
  List<HotDealSiteStat> findHotDealSiteStats(@Param("from") Instant from, @Param("to") Instant to);

  MemoStat findMemoStat(@Param("from") Instant from);

  JiraStat findJiraStat();
}
