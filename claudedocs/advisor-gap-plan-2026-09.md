# 퀀트·advisor 계층 초기 계획 대비 갭 점검 + 보강 계획 (2026-09-19)

- 계기: `tb_advisor_lesson` 이 0건. "처음 계획한 것이 다 들어갔는지, 스케줄러가 전부 수집 중인지" 전수 대조 요청.
- 대조 원본: 수집 계획 `~/.claude/plans/moto-planner-agent-api-memoized-turtle.md`(2026-09-03), advisor 계획 `elegant-singing-glade.md` §1~§12 + 설계 검토 `…-afeedback-critic-…md`(2026-09-13), 프롬프트 v2~v4 계획 `moto-planner-agent-transient-lovelace.md`, 운영 문서 `stock-collect.md`·`stock-advisor.md`.
- 코드 기준: master = origin/master `1951434`(2026-09-19). 미병합 브랜치 0개. 운영에 이 커밋이 배포됐는지는 확인하지 않았다(§4 SQL 로 확인).
- **DB 는 조회하지 못했다.** 런타임 판정(실제 수집 여부·누적 픽 수)은 §4 의 SQL 을 사용자가 돌려야 확정된다.

---

## 0. 결론 요약

| 질문 | 답 |
|---|---|
| `tb_advisor_lesson` 이 왜 비었나 | **설계대로다.** 교훈은 일요일 08:00 `WEEKLY_REVIEW` 의 LESSONS 단계에서만 생기고, 그 단계는 누적 LIVE 픽 ≥ `advisor.lesson.min-picks`(300) 게이트로 잠겨 있다(`WeeklyReviewJob.java:141`). 운영 첫 판단(09-13/14)부터 영업일 5일 × 픽 ≤10 이라 아직 수십 건이다. 게다가 prod 스케줄이 켜진 뒤 첫 일요일이 **09-20(내일)** 이라 WEEKLY_REVIEW 자체가 아직 한 번도 안 돌았을 가능성이 크다(§1) |
| 초기 계획이 다 들어갔나 | advisor: Phase 0~8 + v2~v5 + 채팅 + Responses 전환 전부 구현. stock: 잡 22개 = enum 22개, 스텁 0. 계획이 2차·제외로 둔 것(1분봉·실시간 WS·VIX/WTI/10년물)만 없다 |
| 스케줄러가 전부 수집 중인가 | 코드상 스케줄 9개 중 8개 prod on. **NEWS 만 default·prod 모두 off**(실측 후 켜기로 한 항목). 실제 실행 성공 여부는 §4 SQL |
| 진짜 부족분 | **코드 결함 1**(`shadow.nomem-weeks` 미사용 → LLM_NOMEM 섀도 영구 병행), **구현 갭 4**(교훈 수동 등록 API, 교훈 셀 trend 차원, 보정 표 선행 게이트, 시그널 on/off API), **데이터 갭 2**(VIX·미국 10년물, 뉴스 미가동), **결정 대기 1**(CUSTOM 섹터 사슬), 운영 실측 잔여 다수(§2.3·§3.3) |

---

## 1. `tb_advisor_lesson` 0건의 경로

```
ADVISE(평일 19:30)  ─ 픽 저장 ─▶ tb_advisor_pick (LIVE)
                                     │  countLivePicks() ≥ 300 ?      ← advisor.lesson.min-picks
WEEKLY_REVIEW(일 08:00) ── LESSONS ──┤  아니오 → steps.skip("LESSONS", "누적 LIVE 픽 n < 300")
                                     └  예 → review(폐기) → cells(n≥20) → assist 제안 → reject 규칙(n≥20·|t|≥2·티커 금지·중복) → insert
```

- 게이트 300 은 설계 검토의 "4주 관측으로 바꾸는 것은 노이즈 추종" 결론(하루 유효 관측 2~4)을 반영한 n 게이트다. 픽 7~10/일이면 **약 6~9주 뒤(10월 말~11월 초)** 넘는다.
- 게이트를 넘어도 첫 몇 주는 0건일 수 있다. 셀(국면×시그널 버킷×섹터)마다 n≥20 이 필요하고, 제안이 나와도 `LessonService.reject` 가 근거 부족·중복을 거른다. 이것도 정상이다.
- **수동 등록 경로가 없다.** 운영 문서 §8 은 "trend 조건 교훈은 수동 등록으로만" 이라 했지만 컨트롤러에는 `GET /lessons`·`POST /lessons/{id}/retire` 뿐이다(`AdvisorAdminController.java:228-241`). 지금 넣으려면 psql INSERT 뿐이고 그러면 검증 규칙이 안 걸린다 → §5 A-1.
- 확인 SQL(§4 의 1·2 번): WEEKLY_REVIEW run 이 있는지, LIVE 픽 누적 수.

---

## 2. advisor 계획 대비 대조

### 2.1 구현된 것 (계획 항목 → 코드)

| 계획 | 코드 | 비고 |
|---|---|---|
| run·오케스트레이터·취소·단계 flush | `AdvisorRun`·`AdvisorOrchestrator`·`AdvisorExecution`·`AdvisorSteps` | 취소 API 포함 |
| 시그널 12 + 스크리닝 + rank-IC + 가중치 세트(축소·클립·n_eff 게이트) | `SignalCode`·`FeatureSql`·`CandidateScreeningService`·`SignalIcService`·`SignalWeightMath` | 계획의 `ScreeningSqlBuilder` 는 `FeatureSql` 로 흡수 |
| strict 스키마·후보 enum·가드·입력 동결 | `AdviceSchemaFactory`·`AdviceGuard`·`PromptInputWriter` | Responses `text.format` 으로 이전(09-19) |
| 채점 2단계·배당·정지/상폐·T+5 결정 + 1/20 진단 | `AdviceScoringService`·`ScoreJob` | INDEX σ √h 수정(v2) |
| KPI·보정 표·재현성 Jaccard | `AdvisorKpiService`·`WeeklyReviewJob.reproducibility` | 계획의 `CalibrationService` 는 KPI 서비스로 흡수 |
| 교훈(셀·제안·검증·폐기·태깅) | `LessonService`·`LessonCondition`·`LessonSchemaFactory`·lesson-v2 | condition 에 `trend` 키 있음 |
| 섀도 QUANT_TOPN·LLM_NOMEM·LLM_NONEWS | `AdviseJob.saveQuantShadow/saveLlmShadow` | NOMEM 종료 조건 없음(§2.2 ①) |
| 장중 점검·아침 점검·주간 보고 Slack | `IntradayCheckJob`·`MorningCheckJob`·`WeeklyReviewJob` + 메시지 4종 | |
| 스케줄 4 + 카탈로그 | `Advisor{Advise,Intraday,MorningCheck,WeeklyReview}Scheduler`, `SchedulerCatalog:68-75` | prod 전부 true |
| 관리자 REST(§9) | `AdvisorAdminController` | `POST /lessons` 등록만 없음 |
| v2 시간축·추세·v3 미국 연동·v4 뉴스·v5 KOSPI | `TrendSql`·`GlobalLinkService`·`NewsFeatureService`·advice-v5 | 뉴스는 기본 off |

### 2.2 부족분

| # | 항목 | 현재 | 성격 | 근거 |
|---|---|---|---|---|
| ① | **`advisor.shadow.nomem-weeks`(8) 미사용** — 메모리 활성 뒤 LLM_NOMEM 섀도가 끝나지 않는다(호출·비용 영구 2배). 계획·문서는 "8주간 병행" | yml·Properties 에만 있고 읽는 코드 0건. NONEWS 는 `nonewsShadowOpen()` 으로 8주 창이 구현돼 있어 대조된다 | **코드 결함(잠복)** — 300 픽 뒤 발현 | `AdvisorProperties.java:318`, `AdviseJob.java:258-263,275-277` |
| ② | 교훈 **수동 등록 API** 없음 | psql 만 가능, 검증 규칙 우회 | 구현 갭 | `AdvisorAdminController.java:228-241` |
| ③ | 교훈 생성기 셀에 **trend 차원 없음** — 셀은 regime×시그널×섹터, 스키마는 trend 를 허용 → LLM 이 표에 없는 trend 조건을 제안할 수 있고 근거 셀은 없다 | 문서 §8 에 "후속" 으로 명시 | 구현 갭 | `LessonService.cells():57-102`, lesson-system-v2 |
| ④ | **보정 표를 교훈보다 먼저** 켜라는 검토 권고 — 실적 블록·보정 표·교훈이 같은 게이트(300) | `memoryOn` 하나로 묶임 | 설계 권고와 상이 | `AdviseJob.java:165,173`, `ScoreJob.java:106-107` |
| ⑤ | **시그널 enable/disable API** 없음 — "부호 틀린 시그널은 라이브 전에 제거" 절차가 psql | `tb_advisor_signal_weight.enabled` 컬럼·`enabledWeights()` 는 있음 | 구현 갭 | 컨트롤러에 세트 활성화만 |
| ⑥ | 음의 IC 플래그 "t<−2 두 번 연속" 규칙 | flagged>0 이면 매주 경고(완화판) | 사소 | `WeeklyReviewJob.java:132-135` |
| ⑦ | **VIX·미국 10년물** 입력 | 미수집(KIS 미제공, §3.2) | 데이터 갭 | — |
| ⑧ | `GLOBAL_LINK` 종목 시그널 | 의도적 미충전(IC 부호가 국면에 따라 반전) | 설계 결정 | 문서 §1.2 |
| ⑨ | 뉴스 | 코드 완성·fix 브랜치 병합됨·**양쪽 프로파일 off** | 운영 갭 | `application.yml:372,461` |
| ⑩ | `slack_ts` 저장 → 판단 스레드 자동 후속 | hvy-common `SlackClient` 응답 반환 필요 | 후속 | 문서 §11.5 |
| ⑪ | 채팅 도구 strict 스키마·`ApiLogInterceptor` 헤더 마스킹 | 후속 | 후속 | 문서 §11.1 |
| ⑫ | `/admin/advisor` 화면 | 범위 밖 | — | |

### 2.3 운영 실측·절차 잔여 (코드 변경 없음, 사용자)

- `AdvisorOpenAiManualTest` — Responses `text.format` strict + 2단계 중첩(`trendOutlook`) 수용 실측(09-19 전환 후 미실측).
- `KisIndexPriceManualTest` — 장중 점검 지수 TR ID.
- `POST /jobs/IC_BACKFILL`(baseDate 없이) — KOSPI 한정(v5) 뒤 IC 재기준화 1회. 증분은 자동 재기준화되지 않는다.
- 추세 임계 10년 분포 SQL(문서 §3) → `advisor.trend.*` 조정.
- 첫 WEEKLY_REVIEW(09-20) 뒤 `tb_advisor_prompt_input.options_json.model` 이 assist 인지(문서 §12).
- 섹터 채점 업종 지수 코드 동일성(불일치면 MV 폴백 자동).
- 3개월 규칙: LIVE 부가가치 ≈0 이면 LLM 설명 전용 전환(사전 결정, 문서 §4).

---

## 3. stock 수집 계획 대비 대조

### 3.1 잡·스케줄 (코드 확정)

| 스케줄러 | cron(KST) | default / prod | 내용 |
|---|---|---|---|
| `StockMasterScheduler` | 평일 05:30 | false / **true** | MASTER → HOLIDAY |
| `StockDailyCollectScheduler` | 평일 18:30 | false / **true** | DAILY 10단계: INDEX → PRICE → VALUATION → INVESTOR → MARKET_INVESTOR → ETF_NAV → STATS(`kis.stats.enabled`) → CA_HINT → VALIDATE → DERIVED |
| `StockOverseasScheduler` | 화~토 06:30 | false / **true** | OVERSEAS_DAILY(29심볼) |
| `StockWeeklyScheduler` | 일 03:00 | false / **true** | WEEKLY 5단계: CORP_ACTION → STOCK_INFO → ADJUST_FACTOR → FINANCIAL → DERIVED_FULL |
| `StockNewsScheduler` | 평일 08:05~19:35 30분 | false / **false** | NEWS — **한 번도 안 돈다** |
| `Advisor*Scheduler` 4종 | 19:30~19:55 / 12:00 / 07:30 / 일 08:00 | false / **true** | ADVISE·INTRADAY·MORNING_CHECK·WEEKLY_REVIEW |

수동 전용 8개(백필류·RELOAD)는 계획대로. 잡 구현체 22 = `CollectJobType` 22, TODO·스텁 0(`KisMarketDataPort:26` "2차 실시간 웹소켓" 주석 1건만).

### 3.2 계획 데이터셋 ↔ 구현

| 항목 | 상태 |
|---|---|
| 휴장일·지수 일봉·원주가 일봉·밸류에이션·종목/시장별 투자자·ETF NAV·시장통계·기업행사(배당 포함)·수정계수·재무 6종·마스터 SCD2·업종/테마 매핑·해외 지수/ETF/환율 29심볼 | **구현·스케줄됨** |
| 상폐 종목 이력(생존편향 보강) | **부분** — WEEKLY STOCK_INFO 가 구축일 이후 상폐만 비활성 행으로 보존. 과거 상폐 이관은 원천 없음(pykrx 삭제) |
| 뉴스 | 구현·**스케줄 미가동** |
| **VIX·미국 10년물·WTI** | **미구현** — KIS 미제공(`stock-seed.sql:6` 주석), 계획도 "스키마만 열고 P2". 외부 소스 필요 |
| 실시간 WS·1분봉 | 미구현 — 계획이 2차로 명시 |
| **CUSTOM 섹터 사슬** | **미완** — `tb_stock_global_sector_map` 시드는 `tb_stock_sector_map.source='CUSTOM'` 행을 전제하나 그 행을 만드는 코드·상수·읽는 코드 0건(09-13 발견, 사용자 결정 대기). advisor 는 이 맵을 안 쓴다 |

### 3.3 운영 실측 잔여 (`stock-collect.md` §10, 메모리)

- 단위 미확인 4: 투자자·재무 금액, `hts_avls`(×1e8), `lstn_stcn`(천 단위?), 예탁원 배정율.
- `KisKsdInfoManualTest`(연속조회 페이지 반복 여부 → `ksdRepeatedPages`), `KisMarketInvestorManualTest`(**백필 전 필수**), `KisThemeFileManualTest`(꼬리 폭), 재무 3종 필드명, 거래량 보정, `KisErrorCode` 추정값.
- 백필 후속(메모리 09-08~09-12): `INVESTOR_BACKFILL` 재트리거(PAUSED 해소), `ETF_NAV_BACKFILL`·`MARKET_INVESTOR_BACKFILL`·`FINANCIAL_BACKFILL(reset)`, ETF NAV migrate 적용 → CORP_ACTION 전체 재수집 → ADJUST_FACTOR, `REINDEX INDEX CONCURRENTLY idx_stock_daily_price_date`. 완료 여부는 §4 SQL 7·8 로 본다.

---

## 4. 런타임 확인 SQL (사용자 실행 — 이 결과가 나와야 "수집 중" 이 확정된다)

```sql
-- 1) advisor 잡별 실행 이력 — WEEKLY_REVIEW 행이 없으면 아직 한 번도 안 돈 것
SELECT job_type, status, COUNT(*) n, MAX(base_date) last_base, MAX(started_at) last_start
FROM tb_advisor_run GROUP BY 1, 2 ORDER BY 1, 2;

-- 2) 교훈 게이트 대비 누적 LIVE 픽 (WeeklyReviewJob.countLivePicks 와 동일 정의)
SELECT COUNT(*) AS live_picks, 300 AS gate
FROM tb_advisor_pick p JOIN tb_advisor_advice a USING (advice_id) WHERE a.variant = 'LIVE';

-- 3) 변형별 판단 건수·발행
SELECT variant, COUNT(*) n, MIN(base_date) first, MAX(base_date) last, COUNT(published_at) published
FROM tb_advisor_advice GROUP BY 1 ORDER BY 1;

-- 4) 채점 진행 (h=5 SCORED 가 쌓여야 KPI·교훈 셀이 생긴다)
SELECT horizon_days, stage, status, COUNT(*) FROM tb_advisor_candidate_score GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;

-- 5) IC 범위·활성 가중치 세트 (source 가 SEED 면 IC_BACKFILL 미실행, KOSPI 전환 뒤 BACKFILL 재실행 여부는 created_at 로)
SELECT MIN(trade_date) ic_from, MAX(trade_date) ic_to, COUNT(DISTINCT trade_date) days FROM tb_advisor_signal_ic_daily;
SELECT weight_set_id, source, as_of, round(n_eff::numeric, 1) n_eff, is_active, created_at
FROM tb_advisor_weight_set ORDER BY weight_set_id DESC LIMIT 5;

-- 6) stock 스케줄 run 최근 10일 — DAILY 는 영업일마다 SUCCESS/PARTIAL, steps 전부 OK 여야 한다
SELECT job_type, trigger_type, status, target_date, started_at, metadata_json -> 'steps' AS steps
FROM tb_stock_collect_run
WHERE started_at >= NOW() - INTERVAL '10 days'
  AND job_type IN ('DAILY', 'WEEKLY', 'OVERSEAS_DAILY', 'MASTER', 'HOLIDAY', 'NEWS')
ORDER BY started_at DESC;

-- 7) 테이블별 신선도 — 직전 영업일이 아니면 그 단계가 결손
SELECT 'daily_price' t, MAX(trade_date)::text d FROM tb_stock_daily_price UNION ALL
SELECT 'index_daily', MAX(trade_date)::text FROM tb_stock_index_daily UNION ALL
SELECT 'investor', MAX(trade_date)::text FROM tb_stock_investor_daily UNION ALL
SELECT 'market_investor', MAX(trade_date)::text FROM tb_stock_market_investor_daily UNION ALL
SELECT 'valuation', MAX(trade_date)::text FROM tb_stock_valuation_daily UNION ALL
SELECT 'market_stat', MAX(trade_date)::text FROM tb_stock_market_stat_daily UNION ALL
SELECT 'etf_nav', MAX(trade_date)::text FROM tb_stock_etf_nav_daily UNION ALL
SELECT 'daily_metric', MAX(trade_date)::text FROM tb_stock_daily_metric UNION ALL
SELECT 'global_market', MAX(trade_date)::text FROM tb_stock_global_market_daily UNION ALL
SELECT 'financial(first_seen)', MAX(first_seen_at)::date::text FROM tb_stock_financial UNION ALL
SELECT 'corporate_action(created)', MAX(created_at)::date::text FROM tb_stock_corporate_action UNION ALL
SELECT 'news(published)', COALESCE(MAX(published_at)::date::text, '없음') FROM tb_stock_news;

-- 8) 백필 체크포인트 잔여 — PAUSED/FAILED 가 남아 있으면 해당 *_BACKFILL 재트리거
SELECT job_type, status, COUNT(*) FROM tb_stock_collect_checkpoint GROUP BY 1, 2 ORDER BY 1, 2;
```

판정 기준: 1 에 `WEEKLY_REVIEW` 없음 = 09-20 첫 실행 대기 / 2 가 300 미만 = 교훈 0건 정상 / 6 의 DAILY steps 에 FAILED = 그날 `data_quality=DEGRADED` / 7 의 news 가 '없음' = NEWS 미가동(정상, off) / 8 에 PAUSED = 백필 재트리거 잔여.

---

## 5. 보강 계획 (추가 작업 후보, 우선순위순)

각 항목은 **브랜치 1개·커밋 ≥1·master 직접 커밋 금지**, 주석 한글·함수 단위, `advisor-schema.sql` ↔ `schema-postgres.sql` 미러, 프롬프트 수정 시 `PromptResources.*_VERSION` bump 규약을 따른다.

### A. 교훈 계층 보강 — 브랜치 `feat/advisor-lesson-ops` (권장 1순위, 규모 중)

| 작업 | 변경 | 검증 |
|---|---|---|
| **A-1 수동 등록 API** `POST /api/advisor/admin/lessons` | 요청 `{scope, condition{regime,trend,signal,op,pct,sector}, observation, evidence{n,from,to,excess,t}, rule}` → `LessonProposalResponse` 1건으로 감싸 **`LessonService.apply()` 재사용**(reject 규칙 그대로: 형식·n≥20·\|t\|≥2·티커 금지·중복). run_id null, model `manual`. 거부는 400 + 사유. `GET /lessons/cells?from&to` 로 셀 표(근거 찾기용) 노출 | `LessonServiceTest` 수동 경로, 컨트롤러 400/200 |
| **A-2 셀에 trend 차원** | `LessonService.cells()` SQL 에 `CASE c.bench_index_code WHEN '0001' THEN a.trend_kospi ELSE a.trend_kosdaq END AS trend_code` 추가, 셀 키 5원(regime\|trend\|signal\|bucket\|sector), 셀 조합에 trend·trend×signal·trend×sector 추가(n≥20 필터 동일). `Cell` 레코드·`reviewPayload` 에 `trend`. lesson-system-**v3**(셀 설명에 trend 열) + `LESSON_VERSION="lesson-v3"` | `LessonServiceTest` 셀 키, `WeeklyReviewJobTest` |
| **A-3 보정 표 선행 게이트** | `advisor.lesson.calibration-min-picks`(기본 100) 신설. `memoryOn` 을 둘로 분리: `scoreboardOn`(실적 블록·보정 표) / `lessonsOn`(교훈). `AdviseJob`·`ScoreJob`·`WeeklyReviewJob` 3곳. NOMEM 섀도는 둘 중 하나라도 켜지면 시작 | `AdviseJobTest` 게이트 조합 3경우 |
| **A-4 NOMEM 섀도 8주 종료(결함 ①)** | `AdviceWriter.firstAdviceDate(AdviceVariant.LLM_NOMEM)` → `nomemShadowOpen(baseDate)` = 첫 NOMEM 판단일 + `shadow.nomem-weeks` 이내(NONEWS 와 동형). 종료 뒤 `steps.skip("SHADOW_NOMEM","메모리 섀도 기간 종료")` | `AdviseJobTest` 창 경계 |
| 문서 | `stock-advisor.md` §3 관리자 API·§8 교훈(수동 등록 절차·trend 셀)·§2 설정 | |

### B. 가중치 운영 — 브랜치 `feat/advisor-signal-toggle` (규모 소)

- **B-1 시그널 on/off API** `POST /api/advisor/admin/weights/signals/{signalCode}?enabled=false`: 활성 세트를 복제해 `source=MANUAL`·reason 에 사유·해당 시그널 `enabled` 반전 → 새 세트 활성화(세트는 새 행 = 이력 원칙 유지). 스크리닝은 `enabledWeights()` 라 즉시 반영, IC 계산은 전 시그널 계속(복귀 판단용).
- B-2 "t<−2 두 번 연속" 플래그: `WeeklyReviewJob` 에서 직전 WEEKLY 세트의 `t_stat` 과 비교해 두 번 연속 <−2 인 시그널만 `warnings` 에 "검토 플래그" 로 승격(지금의 flagged>0 경고는 유지).

### C. VIX·미국 10년물 — 브랜치 `feat/stock-macro-source` (규모 중상, **소스 결정 필요**)

- KIS 가 안 주므로 외부 소스가 필요하다. 후보: **FRED**(`VIXCLS`, `DGS10`, 공식·무료 키, 종가만, T+1), Stooq(`^VIX` CSV, 키 없음, OHLC), Yahoo(비공식). 권장 FRED(안정·공식). 키는 env `FRED_API_KEY`.
- stock: `KisMarketDataPort` 와 별도의 `MacroDataPort` + `FredRestAdapter`, `tb_stock_global_market_daily` 에 심볼 그룹 `M`(macro, close 만) 으로 적재(`symbol=VIX`, `UST10Y`), `OVERSEAS_DAILY` 뒤 단계 `MACRO`(실패 격리), yml `kis.macro.symbols`(이름은 `macro.*` 로 분리 검토). 테이블 추가 없음(22→22).
- advisor: `market.global` 에 VIX 수준·r1·r5 와 10년물 수준·변화, `MorningCheckJob` 판정에 VIX 급등(+σ) 시 CAUTION 가중, 추세 성분은 넣지 않는다(국내 라벨 정의 불변). 프롬프트 v6 (입력 설명 2줄) + `ADVICE_VERSION` bump.
- 룩어헤드: FRED 관측일 = 미국 현지일 → `GlobalLinkService` 와 같은 "현지일 < 국내 기준일" 정렬.

### D. 운영 절차 (코드 0)

- **D-1 뉴스 켜기**: 최신 이미지 배포 확인 → `POST /api/stock/admin/collect/NEWS` 1회 → run 메타 `newest` 가 최근·`api_call_count>1` 확인 → prod `scheduler.stock-news.enabled=true` 재기동 → 며칠 뒤 `NEWS_ENABLED=true`(LLM_NONEWS 8주 섀도 자동 시작). 페이지당 건수 × 5 < 밤사이 건수면 cron 을 24시간으로.
- **D-2 §2.3·§3.3 실측** — 우선순위: `IC_BACKFILL` 재실행(가중치가 양시장 기준으로 남아 있음) > `AdvisorOpenAiManualTest` > `KisMarketInvestorManualTest` > 단위 4건.
- **D-3 CUSTOM 섹터 사슬 결정**: (a) 시드 매핑표 `THEME→CUSTOM` 으로 MASTER 가 `source='CUSTOM'` 행 생성, (b) `tb_stock_global_sector_map` 시드·테이블 제거. **소비자가 없으므로 (b) 또는 보류 권장** — 섹터별 미국 연동(`sectors` 블록에 SOX 등)을 원할 때 (a) 로 살린다.

### 규모·순서

| 순서 | 항목 | 규모 | 선행 |
|---|---|---|---|
| 1 | A(교훈 계층 4건) | 신규 2~3 · 수정 ~8 · 테스트 갱신 4, 중 | 없음 — 300 픽 전에 넣어야 첫 교훈부터 trend 셀·8주 종료가 적용된다 |
| 2 | B(시그널 토글) | 수정 3, 소 | 없음 |
| 3 | D-1·D-2 운영 | 코드 0 | 배포 |
| 4 | C(VIX·10년물) | 신규 5~6 · 수정 ~8, 중상 | **소스·키 결정** |
| 5 | D-3 CUSTOM 결정 | 결정에 따라 소 | 결정 |

---

## 6. 사용자 결정이 필요한 것

1. A-3 보정 표 선행 게이트 값(기본 100 제안) — 검토자 권고를 따를지, 300 단일 게이트를 유지할지.
2. C 의 외부 소스(FRED 권장) 와 키 발급 여부. 미결정이면 C 는 보류.
3. D-3 CUSTOM 섹터 사슬 — 제거(b) / 매핑표(a) / 보류.
4. 교훈 수동 등록(A-1)에 evidence 필수 규칙을 그대로 둘지(권장) — 근거 없는 교훈은 설계상 금지.
