# AI 시장 판단(advisor) 운영 문서

- 모듈 `kr.hvy.blog.modules.advisor`, 테이블 `tb_advisor_*` 15개(`db/advisor-schema.sql`, 시드 `db/advisor-seed.sql`; 13번째 `tb_advisor_morning_check` 는 advice-v3, 14번째 `tb_advisor_chat` 은 chat-v1 §11, 15번째 `tb_advisor_pick_note` 는 note-v1 §1.6), REST `/api/advisor/admin/**`(ROLE_ADMIN)
- 작성 2026-09-13. 계획 원문 `~/.claude/plans/elegant-singing-glade.md`. 수집 계층은 `claudedocs/stock-collect.md`.
- **투자 자문이 아니다.** 개인 실험이며 모든 Slack 메시지에 면책 문구가 고정된다.

## 1. 한 바퀴 (선순환)

| 시각(KST) | 잡 | 내용 |
|---|---|---|
| 18:30 평일 | stock DAILY | 일봉·지표 수집 (advisor 의 입력) |
| 19:30~19:55 5분 간격 | **ADVISE** | 게이트(DAILY 완료·PRICE/DERIVED OK) → 채점·IC 증분 → 시장 특징(지수·수급·해외·섹터·σ + **규칙 추세·관측 기준일·적용 구간**) → 정량 스크리닝(KOSPI 유니버스 `advisor.markets` ≈ 수백 → 컷 → 후보 30, 섹터당 ≤4) → 정량 top-N 섀도 → 프롬프트(recentOutcomes 확정 빈도표는 확정 노트 ≥10 이면, 실적 블록·교훈은 300 게이트 뒤) → LLM 판단(strict JSON, 후보 enum) → 가드 → 저장(`memory_json`)·입력 스냅샷 → **#hvy-advisor 발행** → (메모리가 하나라도 실렸고 `nomem-weeks` 창 안이면) 메모리 없는 LLM 섀도 |
| 07:30 평일 | **MORNING_CHECK** | 06:30 해외 수집 뒤·09:00 개장 전. 밤사이 미국 마감 수익률 × 기준일 β(주 심볼)로 **예상 갭**을 계산해 직전 판단의 지수 방향을 유지/강화/주의 판정(규칙 기반, LLM 없음, 원 판단 불변). `tb_advisor_morning_check` 1행 + Slack 짧은 보고. h=1 채점에서 D+1 시가 갭과 대조(`MORNING`) |
| 12:00 평일 | **INTRADAY** | 직전 영업일 판단을 KIS 현재가로 대조해 일치율·판정을 보고하고, 픽마다 시가 대비·지수 대비 초과·z 를 재 결정론 분류(ON_TRACK·MARKET_DRAG·IDIOSYNCRATIC·OVERSHOOT·FLAT) + assist 회고 1회를 `tb_advisor_pick_note` 에 남긴다(note-v1, §1.6). T+5 채점이 CONFIRMED/REFUTED 로 확정하고, 다음 판단 프롬프트에는 **확정 빈도표(recentOutcomes)만** 들어간다(회고 문장은 기록·보고용) |
| 08:00 일요일 | **WEEKLY_REVIEW** | 확정 재채점 → IC 가중치 세트(n_eff 게이트) → 교훈(누적 픽 게이트) → 동결 입력 재실행 Jaccard → 주간 보고 → 스냅샷 보존 정리 |
| 수동 | SCORE / IC_BACKFILL | 채점 보충 / IC 사전 추정(1회) |

학습 신호는 두 층으로 분리된다(2026-09-13 설계 검토): **시그널 가중치 = 설정 시장(KOSPI) 유니버스 rank-IC**(하루 수백 종목), **LLM 부가가치 = 픽 − 후보군 평균 초과수익**. 픽 적중률로 가중치를 만지지 않는다.

### 1.1 시간축 (advice-v2, 2026-09-13)

판단에는 세 개의 시간축이 있다. 프롬프트·Slack·DB 가 모두 같은 값을 쓴다.

| 축 | 원천 | 누가 정하나 | 채점 |
|---|---|---|---|
| **관측 기준일** `dataAsOf` | 국내 종가 = 기준일, 수급(당일 잠정), 섹터 MV, 미국 지수 = **T-1 마감**(FX 제외 지수 심볼의 최소 날짜, `globalAgeTradingDays` 2 이상이면 Slack ⚠️) | 데이터 | — |
| **적용 구간** `window` | 진입 D+1 시가 ~ 청산 D+h 종가. `TradingCalendar`(휴장일 테이블, 미수집 날짜는 평일=개장) 로 **예정** 계산, 실제는 채점 시 `vw_stock_market_calendar` 로 재확정 | 캘린더 | 픽·INDEX 채점 창 |
| **중기 추세** `market.trend` | 지수별(0001·1001) 규칙 라벨 BULL/SIDEWAYS/BEAR — 성분 5개(종가/MA20, MA20/MA60, MA60/MA120, 60일 수익률 ±5%, MA20 상회 종목 비율 ≥0.60/≤0.40) 각 −1/0/+1 의 합이 ≥+2 강세, ≤−2 약세. **confirm-days(2) 연속 같은 raw 라벨일 때만 전환**(휩소 방지). 정의는 `TrendSql` 하나이고 판단·채점·기저율이 공유 | 규칙(LLM 이 바꿀 수 없음) | 라벨 자체는 정답이므로 채점 대상 아님 |
| **추세 지속 전망** `trendOutlook` | 지수별 `persist`(WITHIN_5D / ABOUT_20D / BEYOND_20D) + `confidence` + `invalidation`(NONE / BELOW_MA20 / BELOW_MA60 / ABOVE_MA20 / ABOVE_MA60 — 수치 레벨은 받지 않는다) | LLM | h=20 진단 패스에서 `TREND`·`TREND_INV` (§7) |

`MarketRegimeCode`(RISK_ON/NEUTRAL/RISK_OFF, 5거래일 위험 선호)와 `MarketTrendCode`(중기 추세)는 **다른 축**이다 — 강세장 안의 단기 위험 회피가 실재하므로 합치지 않는다. 시장 breadth 는 새 MV `mv_stock_market_breadth_daily`(stock 모듈, DAILY DERIVED 가 갱신)에서 온다. 교훈 condition 에 `trend` 키가 추가됐고(lesson-v2) 이 조건은 규칙이 기준일에 확정한 **오늘** 값으로 판정한다(`regime` 조건은 여전히 어제 LIVE 국면).

### 1.3 뉴스·사건 입력 (advice-v4, 2026-09-13 · 원천 교체 2026-09-20) — advisor 주입은 기본 off

비정형 입력은 LLM 의 진짜 우위이지만 **백테스트가 불가능**하므로(헤드라인은 실시간 조회) 가치는 섀도로만 잰다. **2026-09-20 KIS 종합 시황/공시 제목 수집을 제거**했다("내용이 별로 없다" 는 사용자 결정) — 헤드라인 원천은 GDELT(§1.5)이고 `tb_stock_news`·`NewsFeatureService`·`citedNews`·NONEWS 섀도는 source 무관이라 그대로 쓴다. GDELT 헤드라인은 종목 태그가 없어 전부 **시장 헤드라인 슬롯**으로 들어간다(`byTicker` 는 비어 있다). 운영 DB 의 KIS 행은 `DELETE FROM tb_stock_news WHERE source = 'KIS'` 로 지운다(남겨도 무해하나 36h 창의 시장 슬롯을 먹는다).

- 수집(stock 모듈, `NEWS` 잡 = `GdeltCollectJob`, `scheduler.stock-eventfeed` 화~토 06:40·평일 19:20): §1.5. 제목·관측 시각(seendate UTC)·매체 도메인·언어만 있고 본문·종목 태그는 없다. 중복 키 = (source, 정규화 제목 sha256, published_at) + (source, url 해시 `serial_no`) — 같은 기사를 다른 seendate 로 재보고하는 GDELT 특성.
- 주입(advisor, `advisor.news.enabled`): `NewsFeatureService` 가 판단 시각(= min(now, 기준일 `cutoff` 20:00 KST) — 사후 재실행에서도 미래 기사가 새지 않게) 이전 `window-hours`(36) 창에서 시장 헤드라인 ≤12·후보 종목별 ≤3·전체 ≤40 을 골라 `N1…` id 를 붙인다. 프롬프트 `news{asOf, windowHours, columns, market[[id,time,title]], byTicker{tkr:[…]}}` 는 candidates 뒤에 실리고 **news 전용 자 상한(7,000)** 을 넘으면 후보별 → 시장 순으로 먼저 줄인다(뉴스가 후보를 밀어내지 않는다). 룩어헤드 방어는 수집 시각이 아니라 **작성 시각(published_at ≤ 판단 시각)** 이다.
- 출력·가드: 픽마다 `citedNews`(그날 id enum 주입) — 입력에 없던 id 는 인용만 제거(`unknownNews`), 다른 종목에만 태깅된 기사 인용은 제거(`newsMismatch`), 픽은 버리지 않는다. `tb_advisor_advice.news_ids`, `tb_advisor_pick.cited_news` 에 저장. 주간 재현성 재실행은 동결 페이로드의 news id 도 스키마 enum 에 넣는다. Slack 에는 제목 원문을 싣지 않는다(재배포 우려) — thesis 안 요약만.
- 섀도 `LLM_NONEWS`: 뉴스가 실린 첫 LIVE 판단부터 `advisor.shadow.nonews-weeks`(8) 동안 뉴스 블록만 뺀 같은 입력(메모리는 LIVE 와 같음)으로 한 번 더 판단. 요인 분리 — LIVE=뉴스+메모리, LLM_NOMEM=뉴스 있음·메모리 없음, LLM_NONEWS=메모리 있음·뉴스 없음. **뉴스 가치 = LIVE − LLM_NONEWS** 를 `GET /scores/summary` 변형 표에서 8주 뒤 se 와 함께 본다(se 안이면 뉴스 off). 호출 수는 메모리 전 2/일, 후 3/일.

### 1.4 KOSPI 한정 유니버스·근거 전문 Slack (advice-v5, 2026-09-13)

09-11 판단 메시지의 픽 근거가 `…` 로 잘려 읽을 수 없던 문제와 "종목 추천은 KOSPI 로" 결정을 함께 반영했다.

- **말줄임의 원인은 프롬프트가 아니라 Slack 계층**이었다. 프롬프트 200자·가드 400자·DB 600자 모두 여유가 있는데 `DailyAdviceMessage` 가 픽 10개를 section 1개(Block Kit 3,000자 상한)에 몰아넣느라 한글 29자/19자로 잘랐다. v5 부터 **픽마다 section 1개** 에 전문을 싣는다(픽당 ≤ ~830자, 블록 수 고정 14 + 픽 ≤10 = 24 ≤ 50; `pick-max` 를 36 넘게 올리면 상한). 근거 첫 줄, `⚠` 리스크는 같은 인용의 둘째 줄.
- **유니버스 = `advisor.markets`(기본 `[KOSPI]`)**. 필터는 스크리닝·rank-IC 가 공유하는 `FeatureSql.featureCtes()` 의 feat CTE 한 곳(`ms.market_type IN (:markets)`)이라 백분위 점수·유니버스 수·IC 표본이 같은 유니버스를 쓴다. 기동 시 `MarketType` 코드 검증(빈 목록·오타는 기동 실패). run 메타 `markets` 로 어느 유니버스 판단인지 남는다. 종목 헤딩은 `*종목 (n) · KOSPI*`.
- **그대로인 것**: 시장 국면 `kospiDir/kosdaqDir`·추세 전망 `trendOutlook.kosdaq`·KOSDAQ 추세 라벨·채점(지수·breadth MV 기준, strict 스키마와 맞물림), 섹터 지표(`mv_stock_sector_daily` 양시장 전체 — 프롬프트에 "후보 없는 섹터를 주도 섹터로 고르지 말라" 명시), 픽 벤치마크(후보의 `bench_index_code`, KOSPI → 0001), QUANT_TOPN·"후보군 대비" KPI(같은 후보 목록).
- **프롬프트 v5**: 1행 범위(시장 판단은 양지수, 픽은 KOSPI), candidates 가 KOSPI 만임을 명시, thesis **300자**/risk **150자** + 구조(근거 특징 2~3개 → 해석 → 적용 구간 기대 흐름 / 리스크 = 틀리게 만들 조건 + 첫 신호). 가드 400/300·DB 600 은 그대로. 출력 토큰이 늘어난다(관찰 항목).
- **전환 절차(배포 후 1회)**: 저장된 IC 행은 양시장 기준이므로 `POST /api/advisor/admin/jobs/IC_BACKFILL`(baseDate 없이) 로 덮어쓴다(`SignalIcWriter.upsert` 가 (signal_code, trade_date) 키로 교체, BACKFILL 세트 새로 활성화). 증분은 `maxTradeDate()+1` 부터만 계산하므로 **자동으로 재기준화되지 않는다**. 전환일(2026-09-1x, 첫 v5 LIVE run) 을 기록해 둘 것 — 이전 LIVE 픽·교훈 셀·"후보군 대비" 90일 창은 양시장 후보와 섞인다(run 7회 시점이라 실질 영향 없음). 후보가 `pick-min`(3) 아래로 떨어지면 ADVISE 가 FAILED 이므로 첫 주 `metadata.cut` 을 본다.

### 1.5 거시 위험 지표·사건 피드 수집 (2026-09-20, stock 모듈) — advisor 주입은 뒤 단계에서

계획 `~/.claude/plans/moto-planner-agent-pasted-content-id-39-crystalline-rivest.md` 의 Phase 1a. KIS 가 주지 않는 VIX·미국 국채 수익률과 국제 사건 스트레스를 **키 없는 공개 원천**에서 받는다. 아직 프롬프트에는 넣지 않는다 — 시장 시계열은 라이브 섀도(8주 n_eff≈8)로 가치를 잴 수 없어 2015~ 이력 검정(Phase 1b: 규칙 국면 기저선 `REGIME_RULE`)이 먼저다.

| 잡 | 원천 | 테이블 | 스케줄(`scheduler.*`) | 창·백필 |
|---|---|---|---|---|
| `MACRO`(`MacroCollectJob`) | CBOE 일별 지수 CSV(`VIX`, 1990~) · 미국 재무부 일별 수익률 CSV(`UST10Y`·`UST2Y`, 연도별 파일, URL 의 `{year}`) — yml `macro.series` "시리즈:원천:URL" | `tb_stock_macro_daily(series_code, obs_date, value, source, available_from, observed_at)` | `stock-macro` 화~토 06:35·08:35 | 최근 `macro.lookback-days`(10) upsert. 백필 `POST /api/stock/admin/collect/MACRO {"startDate":"2015-01-01"}` |
| `NEWS`(`GdeltCollectJob`) | GDELT DOC 2.0 API — yml `gdelt.themes` "코드:표시명:쿼리" 6개(KR_GEO·KR_GEO_KO·US_CN_TRD·WAR·SEMI_REG·FIN_STRESS) | 시계열 `tb_stock_event_timeline(theme_code, obs_date, source LIVE\|BACKFILL, article_vol, total_vol, vol_ratio, avg_tone)` + 헤드라인 `tb_stock_news(source='GDELT')` | `stock-eventfeed` 화~토 06:40(`cron-am`)·평일 19:20(`cron-pm`) | 시계열 최근 `gdelt.timeline-days`(30) 완결 UTC 일자, 헤드라인 최근 `window-hours`(36). 백필 `{"startDate":"2017-01-01"}` 은 시계열만(source=BACKFILL, 90일 청크) |

- **관측 가능 시각(룩어헤드)**: 거시는 `available_from = obs_date + macro.available-lag-days(1)` 이고 특징 SQL 은 반드시 `available_from <= 기준일 AND obs_date < 기준일` 둘 다 건다. `observed_at` 은 최초 수신 시각이라 정정에도 갱신하지 않는다. GDELT 시계열은 완결된 UTC 일자만 저장하고(오늘 UTC 제외) **LIVE 행은 재수집이 덮어쓰지 않는다**(BACKFILL 만 갱신) — 당시 알 수 있었던 값 보존. 헤드라인은 `published_at = seendate` 로 기존 창 규칙 그대로.
- **라이브 원천 = 백필 원천**: 시리즈당 URL 하나. FRED(`VIXCLS`·`DGS10`)는 익영업일 게시라 T-2 가 되고 Stooq 는 JS 봇 검증 페이지를 돌려줘(2026-09-20 실측) 둘 다 제외했다. 달러지수는 `kis.overseas` 의 `FX@KRW` 가 있어 넣지 않는다. WTI 는 키 없는 안정 원천이 없어 보류. `KisOverseasSymbolManualTest`(미작성)로 KIS 가 VIX·10년물 심볼을 주면 어댑터 대신 `kis.overseas.symbols` 1줄이지만 저장 위치는 그대로 `tb_stock_macro_daily`.
- **GDELT 제약**: 요청 간격 5초 미만이면 429("one every 5 seconds") — 어댑터가 호출 사이 6초를 강제하고 429 는 30초 백오프 3회(2026-09-20 첫 실측은 60초 간격에서도 429 가 섞였다 — 초기 연속 호출 페널티로 추정, `GdeltDocManualTest` 로 재확인). 30일 창·6테마·3모드 = 18호출 ≈ 2분. `timelinetone` 은 짧은 창에서 **시간 단위** 버킷으로 오므로 잡이 UTC 일자로 묶는다(기사량 합, 전체량 합, 기사 수 가중 톤). 원 건수(`article_vol`)는 감사용이고 특징은 `vol_ratio`·`avg_tone` 의 250일 z·백분위만 쓴다. 테마 코드는 10자 이내(`tb_stock_news.provider_code` 폭).
- run 메타: MACRO `series{VIX:{fetched,rows,latest,lagDays}}` — 06:35 실행에서 `lagDays` 가 1 이면 T-1 확보, 2 면 게시 지연(08:35 가 보충). NEWS `themes{code:{timelineRows,articlesFetched,articlesInserted|error}}`. 시리즈·테마 단위로 실패를 격리하고 run 은 PARTIAL(`CollectNotifier` 규칙 그대로). 본문 0바이트·헤더 변경은 조용한 0행이 아니라 실패다.
- 실측(스케줄 on 전, 키 불필요): `MACRO_PROBE=true ./gradlew test --tests "*MacroSourceManualTest"`(형식·lagDays), `GDELT_PROBE=true ./gradlew test --tests "*GdeltDocManualTest"`(30일 창 버킷 해상도·volraw 의 norm·artlist 필드·429 빈도). 통과하면 prod `scheduler.stock-macro.enabled`·`scheduler.stock-eventfeed.enabled` 를 true 로 재기동.

### 1.6 섹터 기간 모멘텀·12:00 오답노트·2계층 메모리 (advice-v6 · note-v1, 2026-09-21)

운영 시작 뒤 사용자가 느낀 세 문제 — 섹터 관점이 5일 하나뿐, 12:00 점검이 "맞았다/틀렸다" 집계에서 끝남, 교훈 주기가 너무 김(300 게이트) — 를 한 브랜치(`feat/advisor-sector-momentum-notes`, 계획 `plan/advisor-sector-momentum-notes.md`)에서 세 축으로 풀었다. 퀀트 규율 검토 권고를 전부 채택한 **사용자 결정 4건**이 설계의 뼈대다.

| 결정 | 내용 | 이유 |
|---|---|---|
| ① 노트의 다음 판단 반영 | **T+5 로 확정된 결정론 빈도표만** 프롬프트에. LLM 회고 문장(deviation·why·hypothesis)은 DB·Slack·관리자 기록용, 프롬프트 미주입(가설 주입 플래그도 구현하지 않음) | D+1 09:00→12:00 3시간 수익률의 분산은 T+5 의 약 9% 이고 종목 1일~1주 구간은 단기 반전 우세라 반나절 방향은 잔여 4.5일에 예측력이 0 이거나 음. 그 위의 "왜" 는 노이즈에 붙인 서사이며 다음날 주입은 n=1 일화 학습 = 교훈 게이트(n≥20·\|t\|≥2)의 우회 |
| ② 섹터 모멘텀 | **프롬프트 v6 + 정량 시그널 동시**(점수 반영은 배포 후 `IC_BACKFILL` 뒤) | 프롬프트만 바꾸면 LIVE−QUANT_TOPN(LLM 부가가치)에 특징 효과가 섞인다. "꾸준히 오른" 은 **시장 대비 초과 3구간**으로 — 절대 양수 3구간은 강세장 전부 통과·약세장 공집합이라 섹터 선택이 아니라 시장 타이밍으로 퇴화 |
| ③ 교훈 체계 | **그대로 + t 통계의 se 를 base_date 클러스터로 보정**(300 게이트 유지) | 게이트를 낮추면 n≥20 셀은 regime 단독만 남아 시그널×섹터 교훈은 0건 또는 우연 통과. 진짜 문제는 같은 날 픽의 공통 요인·5일 창 겹침으로 픽 단위 se 가 1.7~2배 과소인 것 |
| ④ 가치 측정 | **LLM_NOMEM 섀도 8주 병행**(judge +1회/일) + `nomem-weeks` 미사용 결함 동시 수정 + 사전 등록 판정(§8) | 8주(n_eff≈13, se≈0.55%/5일)로는 "행동을 바꾸는가·크게 해치는가" 만 답할 수 있다. 0.2%/5일 을 t=2 로 보려면 약 1,200일 |

**축 A — 섹터 기간 모멘텀(advice-v6).** 원천은 `mv_stock_index_metric` 의 **KOSPI 업종 지수** 행(`tb_stock_sector_map.sector_code` = `tb_stock_index_daily.index_code`, 이미 `ret_5d/20d/60d` 계산됨). 업종 지수는 KOSPI 종목만으로 구성된 시총가중 공식 지수라 기존 `mv_stock_sector_daily` 동일가중(양시장, KOSDAQ 종목 수가 지배)과 KOSPI 후보의 불일치가 사라진다. sectors 표에 `rs5/rs20/rs60`(업종 지수 − KOSPI 0001, 소수), `mom`(세 구간 백분위 평균, members≥5 섹터끼리, 등가중·튜닝 금지), `consistent`(rs5>0∧rs20>0∧rs60>0), `overheated`(업종 5일 수익률 > `overheated-sigma`(2.0)×σ5d(0001)) 가 **맨 뒤에** 붙고 top 8 정렬이 `cw5`→`mom` 으로 바뀌었다. 후보 행에도 `secRs60`·`secCons`(1/0/null — 업종 지수 없으면 null) 를 실어 LLM 이 두 표를 조인하지 않게 한다. 정량 시그널 `SECTOR_MOM_20D`·`SECTOR_MOM_60D`(feat CTE `sector_rs_20d/60d`, base 0.05, IC 학습) 는 시드에 있지만 **활성 세트에 행이 없어 `IC_BACKFILL` 재실행 전엔 점수에서 빠진다**(프롬프트 특징은 즉시). 주도 섹터 enum 은 후보가 있는 섹터로 한정(bottom 을 고를 수 없음), `SectorCall.consistent` 저장 → SECTOR 채점 consistent 분할(§7). 가드는 `secCons=0` 또는 `overheated` 섹터 LONG 픽의 확신을 `non-consistent-conviction-cap`(0.70) 으로 **기계 클램프**(stats `capNonConsistent`·`capOverheated`) — 프롬프트 문구만으로 두지 않는다. 지수가 없는 섹터는 rs·mom null·consistent false 로 규칙 8 폴백("3구간 초과 미충족" reason) 경로만 돈다.

**축 B — 12:00 오답노트(note-v1).** `IntradayCheckJob` 을 `AdvisorSteps` 로 단계화: `INDEX`(격리, 지수 2+벤치) → `PICKS`(필수, KIS 조회·정량·`PickDeviation.classify` 결정론 분류, `KisPriceResponse.Output` 에 시가·고저·기준가 맨 뒤 추가) → `REFLECT`(격리, FLAT 아닌 픽만 assist 1회, `intraday-note-system-v1.md`·`NoteSchemaFactory`, 전부 FLAT 이거나 `note.enabled=false` 면 SKIPPED) → `SAVE`(필수, check 1행) → `NOTES`(격리, note N행 — 노트 테이블이 아직 없어도(배포 전) 기존 점검은 산다) → `PUBLISH`(격리, Slack 픽 줄 ≤10). 어느 격리 단계가 죽어도 일치율·판정·Slack 은 유지되고 run 은 PARTIAL. 정량: `sinceOpen = current/open−1`(**진입가 대비, 1차 지표** — D+1 시가 진입 규약과 정합), `excess = sinceOpen − benchSinceOpen`(지수 시가 있으면 OPEN 기준, 없으면 전일 대비 차 PREV_CLOSE 폴백 → `tags_json.excessBasis`), `z = excess / (vol20×√(3/6.5))`. 분류 `PickNoteClass`: FLAT(\|z\|<1, 회고 생략) · ON_TRACK(방향 일치) · MARKET_DRAG(시장 동반 하락 — 지수 대비 초과는 작지만 픽 자체가 유의하게 하락, 자체 등락 z ≤ −임계) · IDIOSYNCRATIC(지수 대비 −1σ 이상 뒤처짐) · OVERSHOOT(z≥2, 되돌림 경고), AVOID 는 부호 반전. 회고 출력 {deviation ≤120·why ≤200·hypothesis ≤120(티커·종목명·날짜 금지 — 위반은 null 저장)·tags{signals⊂SignalCode, sector, regime}}. 테이블은 **append-only·bitemporal**: `UNIQUE(check_id, ticker)`, 12:00 관측을 UPSERT 로 덮지 않고, 집계는 (advice, ticker) 별 `noted_at` 가장 이른 행(정규 점검 밖 `offHours=true` 는 기록만). `ScoreJob` 이 h=5 PROVISIONAL 저장 직후 `finalize` — `final_excess = excess_ret`, `status = sign(excess_rate)==sign(final_excess) ? CONFIRMED : REFUTED`(12:00 초과가 없던 행은 OPEN 유지 + finalized_at 만). 직후 호출이 격리 실패했던 판단은 `ScoreJob` 의 `NOTE_SWEEP` 단계(OPEN·미확정 노트가 있는 판단을 매 run 훑어 재시도, 결정 호라이즌 채점이 없으면 0건)가 보충한다. 12:00 값은 이 테이블에만 살고 픽·후보·채점·IC·교훈 SQL 은 읽지 않는다(`AdvisorSqlBoundaryTest` 가 소스 문자열로 단언).

**축 C — 2계층 메모리.** 빠른 층 = `recentOutcomes`(`RecentOutcomesService`): 기준일 앞 `note.window-trading-days`(20) 거래일의 확정 노트를 `finalized_at·noted_at ≤ 기준일 note.cutoff(20:00 KST)` 로 읽어(사후 재실행에서 미래 확정 차단) `class × secCons` 빈도표 `{windowTradingDays, finalizedAsOf, columns:[class,secCons,n,confirmRate,meanFinalExcess,se,underpowered], rows}` 로 만든다. 확정 노트 < `min-finalized`(10) 이면 블록 생략(run 메타 `skip.NOTES`), 행은 n 상위 `max-rows`(5), `underpowered = n<30`, secCons 없음은 `"-"`. 프롬프트 위치는 scoreboard 뒤·lessons 앞, 지시는 "확신을 **한 단계(0.05) 안에서만**, underpowered 는 참고만, 종목 추가·제거·순위 변경 금지". 느린 층 = scoreboard·lessons(300 게이트 그대로). 헤더 `memory_json = {recentOutcomes: 행수, lessons: [id], scoreboard: bool}` 은 **하나도 실리지 않으면 NULL**(NOMEM 섀도도 NULL) — `AdviceWriter.firstMemoryAdviceDate()`(LIVE 의 MIN(base_date) WHERE memory_json IS NOT NULL) 가 NOMEM 창의 시작점이다. `SHADOW_NOMEM` 실행 조건은 `memoryInjected && nomemShadowOpen(baseDate)`(첫 메모리 판단일 + `shadow.nomem-weeks`(8) 이내, NONEWS 와 동형) 로 바뀌어 **2026-09-21 까지 `nomem-weeks` 를 읽는 코드가 없어 NOMEM 이 영구 병행이던 결함이 수정**됐다(skip 사유 "메모리 미주입"/"섀도 기간 종료"). 300 전에는 scoreboard·lessons 가 비어 NOMEM = "노트만 뺀 것" 이라 정확히 1요인이다. 주간 보고 KPI 블록에 `LIVE vs NOMEM(최근 4주): 픽 Jaccard 평균·|Δconviction| 평균 (n=일수)` 와 `OPEN 노트 10영업일 초과: N건`(finalize 정지 경보, `PickNoteRepository.countStaleOpen`) 2줄이 붙는다(`MEMORY` 단계, 격리). 교훈 셀의 t 는 base_date 클러스터 se(`LessonService.clusterT`, D<2 → 0, `Cell.nDays`) 로 바뀌었고 `lesson-system-v2.md` 에 설명 한 줄만 보탰다(스키마 불변, 버전 유지).

### 1.2 미국 연동 (advice-v3, 2026-09-13)

19:30 판단 시점의 미국 데이터는 **T-1 현지일 마감**이며 이미 국내 종가에 반영된 과거다(미국 당일 세션은 22:30 개장). 그래서 판단 입력에는 **연동 강도만** 넣고, 미국 정보가 전방인 유일한 구간인 **07:30 아침 점검**에서 예측 가치를 취한다.

- 입력 `market.link[{kr, us, beta, corr, n}]`(`GlobalLinkService`): 쌍은 yml `advisor.morning.link-pairs`(기본 KOSPI:SPX·SOX, KOSDAQ:COMP·SOX), 창 60 국내 거래일. **정렬**: 국내 d일 수익률 ↔ 현지일 ∈ [국내 직전 거래일, d−1] 인 미국 세션(월요일 ↔ 금요일). 그 구간에 미국 세션이 없으면(미국 휴장) 짝을 짓지 않는다 — 같은 미국 수익률을 두 국내일에 재사용하지 않기 위해서다. `market.global` 에 r20/r60 이 붙었고, 조회 필터가 `현지일 < 기준일` 로 바뀌어 사후 재실행(`baseDate=`)에서도 밤사이 결과가 새어 들지 않는다(v2 까지의 결함).
- 아침 점검(`MorningCheckJob`): 대상 = 직전 영업일 LIVE. 예상 갭 = β(지수별 주 심볼, 기준일 기준) × 밤사이 미국 1일 수익률, 임계 = `sigma-multiple`(1.0) × σ_1d(직전 60일). |갭| < 임계 → **HOLD**, 어제 방향과 같은 부호 → **REINFORCE**, 반대 부호(또는 NEUTRAL 예측에 큰 갭) → **CAUTION**. 전체 판정은 지수별 중 가장 심각한 것. 기준일에 미국이 휴장이면 새 정보가 없으므로 HOLD 로 기록만, 미국 데이터가 `max-us-lag-days`(4) 보다 오래되면 SKIPPED. **원 판단·픽·채점은 그대로** — 점검은 `tb_advisor_morning_check` 별도 행이고 관리자 `GET /advices/{id}` 의 `morningCheck` 로 보인다.
- `SignalCode.GLOBAL_LINK` 종목 시그널은 채우지 않는다(종목별 매핑 없음, 고β 는 강세장에 좋고 약세장에 나쁘므로 IC 부호가 국면에 따라 뒤집힘). VIX·미국 10년물은 `KisOverseasSymbolManualTest` 로 KIS 가 심볼을 주는지 실측한 뒤 yml `kis.overseas.symbols` 에만 추가하면 된다.

## 2. 설정 (application.yml)

| 키 | 기본 | 뜻 |
|---|---|---|
| `advisor.enabled` | `${ADVISOR_ENABLED:false}` | false 면 ChatClient·잡·컨트롤러 전부 미등록 |
| `spring.ai.openai.api-key` | `${OPENAI_API_KEY:}` | 키. Spring AI 자동구성은 없다(OpenAI 스타터·SDK 미포함, §12) — `AdvisorProperties` 가 이 키를 Environment 로 읽고 `AdvisorAiConfig` 가 Responses 모델 3개를 직접 조립 |
| `advisor.model.judge` / `assist` | `${ADVISOR_JUDGE_MODEL:}` / `${ADVISOR_ASSIST_MODEL:}` | 판단용 / 보조용 모델 ID. **코드에 박지 않는다**. 추론 모델이면 temperature 미설정 |
| `advisor.model.judge-max-completion-tokens` / `assist-max-completion-tokens` | 8000 / 2000 (yml 20000 / 10000) | Responses `max_output_tokens`(추론 토큰 포함, 비용 손잡이). 잘리면 잡 FAILED `"max_output_tokens 에서 잘렸습니다"` — 상한을 올린다 |
| `advisor.model.max-retries` / `timeout-seconds` | 3 / 120 | `OpenAiResponsesClient` 재시도(429·408·409·5xx·네트워크, Retry-After ≤30s) / `openAiRestClient` 응답 타임아웃. 세 모델 공통 |
| `advisor.cost.*` | 0 | 100만 토큰당 USD. 채우면 run.cost_usd 계산 |
| `advisor.prompt.version` | `advice-v6` | 프롬프트 버전(표기용 — 실제 로드는 `PromptResources.ADVICE_VERSION`·파일명 `prompts/advisor/advice-system-v6.md`). 파일을 고치면 둘을 같이 올린다 |
| `advisor.advise.non-consistent-conviction-cap` / `overheated-sigma` | 0.70 / 2.0 | advice-v6 가드 클램프: `secCons=0`(소속 업종 지수가 1주·1개월·3개월 중 하나라도 시장 미달) 또는 `overheated` 섹터 LONG 픽의 확신 상한(CONVICTIONS 값 중 하나) / 섹터 과열 = 업종 지수 5일 수익률 > 배수 × σ_5d(KOSPI). stats `capNonConsistent`·`capOverheated` 로 준수율 관찰(§1.6) |
| `advisor.note.enabled` | `${ADVISOR_NOTE_ENABLED:true}` | false 면 12:00 회고 LLM 호출(REFLECT)만 건너뛴다 — 정량·분류 노트는 그대로 저장(무료·결정론) |
| `advisor.note.z-threshold` / `max-reflect-picks` | 1.0 / 10 | \|z\| 미만이면 FLAT(회고 생략) / 회고 호출 1건에 넣는 픽 상한(\|z\| 큰 순, assist 1회/일) |
| `advisor.note.window-trading-days` / `min-finalized` / `max-rows` / `cutoff` | 20 / 10 / 5 / 20:00 | recentOutcomes 집계 창(거래일) / 확정 노트가 이보다 적으면 블록 생략 / 빈도표 최대 행(class × secCons, n 상위) / 확정 마감(KST) — 사후 재실행에서 이 시각 이후 확정·관측이 새지 않게(`news.cutoff` 와 같은 뜻) |
| `advisor.shadow.nomem-weeks` | 8 | 메모리(recentOutcomes·lessons·scoreboard 중 하나라도)가 처음 실린 LIVE 판단부터 메모리 없는 LLM 섀도(LLM_NOMEM) 병행 기간. **2026-09-21 이전엔 읽는 코드가 없어 영구 병행이었다**(§1.6·§8 사전 등록 판정) |
| `advisor.markets` | `[KOSPI]` | 스크리닝·rank-IC 유니버스 시장(`MarketType` 코드). 빈 목록·오타는 기동 실패. **바꾸면 `IC_BACKFILL`(baseDate 없이) 재실행**으로 IC 재기준화(§1.4) |
| `advisor.trend.bull-threshold` / `bear-threshold` | 2 / −2 | 추세 성분 합 임계. 배포 후 10년 라벨 분포(§3 SQL)로 조정 — 보합 <15% 면 ±3, >55% 면 ret60 컷 0.03 |
| `advisor.trend.ret60-threshold` / `breadth-high` / `breadth-low` | 0.05 / 0.60 / 0.40 | 60일 수익률·MA20 상회 비율 성분 컷 |
| `advisor.trend.confirm-days` | 2 | 전환 확인 연속 거래일 |
| `advisor.trend.score-horizon-days` | 20 | 추세 전망 채점 창. **`diagnostic-horizons` 에 없으면 기동 시 WARN 이고 채점이 영원히 안 돈다** |
| `advisor.trend.invalidation-tolerance-days` | 2 | TREND_INV 적중: 무효화 발동일과 전환일의 허용 거리 |
| `advisor.morning.link-pairs` | `0001:SPX, 0001:SOX, 1001:COMP, 1001:SOX` | β·상관 쌍. 지수별 첫 쌍이 아침 점검 예상 갭의 주 심볼 |
| `advisor.morning.link-window-days` / `sigma-multiple` / `max-us-lag-days` | 60 / 1.0 / 4 | β 창(국내 거래일) / 아침 판정 임계 배수(× σ_1d) / 미국 데이터 허용 지연(캘린더일, 초과면 SKIPPED) |
| `scheduler.advisor-morning-check.enabled` | default false / prod true | 07:30 MON-FRI, 기동 시 평가 |
| `advisor.news.enabled` | false | 뉴스 입력 on/off. 켜면 news 블록·citedNews·LLM_NONEWS 섀도가 함께 켜진다. 선행: `scheduler.stock-eventfeed` 로 `tb_stock_news`(source=GDELT) 가 쌓여 있어야 함 |
| `advisor.news.window-hours` / `market-limit` / `per-ticker-limit` / `total-limit` / `max-chars` / `title-chars` / `cutoff` | 36 / 12 / 3 / 40 / 7000 / 120 / 20:00 | 창·상한·news 블록 자 상한·판단 마감(사후 재실행 룩어헤드 상한) |
| `advisor.shadow.nonews-weeks` | 8 | 뉴스 없는 섀도 병행 기간 |
| `macro.series` / `lookback-days` / `available-lag-days` / `timeout-seconds` | VIX(CBOE)·UST10Y·UST2Y(재무부) / 10 / 1 / 30 | 거시 시리즈 "시리즈:원천:URL"(§1.5). 원천 추가는 `MacroCsvSource` 구현 1개, 시리즈 추가는 `MacroSeries` 상수(원천 열 이름) + yml 1줄 |
| `gdelt.themes` / `timeline-days` / `window-hours` / `max-records` / `min-interval-ms` / `max-retries` / `retry-backoff-ms` | 테마 6 / 30 / 36 / 60 / 6000 / 3 / 30000 | 사건 피드(§1.5). 테마 추가는 yml 1줄(코드 10자 이내), 호출 간격은 GDELT 5초 제한보다 여유 있게 |
| `scheduler.stock-macro.enabled` / `scheduler.stock-eventfeed.enabled` | false (default·prod 모두) | `MacroSourceManualTest`·`GdeltDocManualTest` 실측 뒤 prod true 로. eventfeed 는 `cron-am`/`cron-pm` 두 cron·`lock-name-am`/`-pm` 두 락 |
| `advisor.horizon-days` | 5 | 결정 호라이즌. 채점·KPI·학습 전부 이 값 |
| `advisor.candidate-limit` / `max-per-sector` / `pick-min` / `pick-max` | 30 / 4 / 3 / 10 | 깔때기 |
| `advisor.advise.deadline` | 19:55 | 이후에도 DAILY 미완료면 SKIPPED + #hvy-error |
| `advisor.lesson.min-picks` | 300 | 실적 블록·보정 표·교훈 게이트(누적 LIVE 픽) |
| `advisor.ic.min-n-eff` | 24 | 가중치 세트 갱신 게이트(≈120 영업일). 사전 추정으로 충족 |
| `advisor.ic.incremental-max-days` | 45 | 증분(ADVISE·WEEKLY_REVIEW·SCORE)이 감당할 최대 공백(캘린더일). 초과분은 계산하지 않고 warnings 에 `IC 공백 …` + 메타 `icGapFrom` 을 남긴다 → `POST /jobs/IC_BACKFILL?baseDate=<icGapFrom>` 로 보충. IC 행이 없는 첫 ADVISE 가 2020 년부터 6년치를 SQL 한 번에 돌던 2026-09-13 결함 방지 |
| `advisor.shadow.reproducibility-runs` | 3 | 주간 재현성 재실행 횟수(0 이면 끔) |
| `advisor.chat.enabled` | `${ADVISOR_CHAT_ENABLED:false}` (prod 기본 true) | Slack 채팅 봇(§11). advisor.enabled 가 false 면 무관하게 미등록 |
| `advisor.chat.app-token` / `channel-id` / `allowed-user-ids` | `${SLACK_APP_TOKEN:}` / `${ADVISOR_CHAT_CHANNEL_ID:}` / `${ADVISOR_CHAT_ALLOWED_USERS:}` | Socket Mode app-level 토큰(xapp-, connections:write) / #hvy-advisor 채널 ID(C…) / 답을 받을 사용자 ID(U…, 쉼표). **하나라도 비면 WARN 만 남기고 연결하지 않는다(기동은 됨)**. bot 토큰은 `slack.token` 재사용 |
| `advisor.chat.model` / `reasoning-effort` / `max-completion-tokens` | `${ADVISOR_CHAT_MODEL:}` / `${ADVISOR_CHAT_REASONING_EFFORT:}` / 6000 | 채팅 모델(비면 assist 모델) / Responses `reasoning.effort`(none·minimal·low·medium·high·xhigh·max, **비면 미전송 = OpenAI 서버 기본**) / Responses `max_output_tokens`(추론 토큰이 같이 소모되므로 3000→6000, 2026-09-19) |
| `advisor.chat.max-calls-per-tool` / `max-total-tool-calls` | 6 / 12 | 질문 1건의 도구 호출 상한(ToolCallingManager). 초과는 오류 응답으로 돌려 모델이 마무리 |
| `advisor.chat.thread-history-limit` / `max-history-chars` | 30 / 12000 | 스레드 히스토리 메시지 수·문자 상한(루트 보존, 오래된 것부터 제거) |
| `advisor.chat.tool-row-limit` / `tool-timeout-seconds` / `answer-timeout-seconds` | 50 / 5 / 180 | 도구 행 상한 / 도구 SQL statement_timeout / 질문 1건 소프트 마감(도구가 deadline 오류를 돌려주고 모델이 마무리) |
| `advisor.chat.daily-token-budget` / `per-user-cooldown-seconds` | 300000 / 20 | 일일 토큰 예산(입력+출력, KST 자정, 0 이면 무제한) / 같은 사용자 최소 간격. 거부는 SKIPPED + 스레드 한 줄 |
| `advisor.chat.max-blocks` / `disconnect-alert-cooldown-minutes` / `dedup-ttl-minutes` | 20 / 30 / 10 | 답글 블록 상한 / WebSocket 끊김 경보(#hvy-notify) 최소 간격 / event_id 중복 제거 Redis TTL |
| `scheduler.advisor-{advise,intraday,weekly-review}.enabled` | default false / prod true | 기동 시 평가 |

## 3. 배포 절차 (처음 1회)

1. Slack 워크스페이스에 `#hvy-advisor` 채널 생성(없으면 발행 실패가 로그로만 남는다).
2. env: `OPENAI_API_KEY`, `ADVISOR_JUDGE_MODEL`, `ADVISOR_ASSIST_MODEL`, `ADVISOR_ENABLED=true`. 모델 ID 는 OpenAI 모델 목록에서 확정.
3. psql: `db/advisor-schema.sql` → `db/advisor-seed.sql` (재실행 안전; v3 의 `tb_advisor_morning_check` 는 `CREATE TABLE IF NOT EXISTS` 라 재적용으로 생긴다). **advice-v2 를 기존 설치에 올릴 때**는 같은 파일 하단의 마이그레이션 블록(`ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS trend_kospi …`, `tb_advisor_call_score.predicted/actual_dir VARCHAR(20)`, `event_date`)이 함께 실행되는지 확인한다 — `predicted` 확대를 빠뜨리면 `BEYOND_20D`(10자) 저장이 조용히 실패한다. stock 쪽은 `cat db/stock-derived-rebuild.sql db/stock-derived.sql | psql -1` 로 breadth MV 를 만든다.
   추세 임계 점검(10년 라벨 분포, 목표 강세≈40 / 보합≈35 / 약세≈25%):
   ```sql
   WITH scored AS (SELECT index_code, trade_date,
       (CASE WHEN close_value > ma_20 THEN 1 WHEN close_value < ma_20 THEN -1 ELSE 0 END)
     + (CASE WHEN ma_20 > ma_60 THEN 1 WHEN ma_20 < ma_60 THEN -1 ELSE 0 END)
     + (CASE WHEN ma_60 > ma_120 THEN 1 WHEN ma_60 < ma_120 THEN -1 ELSE 0 END)
     + (CASE WHEN ret_60d >= 0.05 THEN 1 WHEN ret_60d <= -0.05 THEN -1 ELSE 0 END) AS sc
     FROM mv_stock_index_metric WHERE index_code IN ('0001','1001') AND trade_date >= '2016-01-01')
   SELECT index_code, CASE WHEN sc >= 2 THEN 'BULL' WHEN sc <= -2 THEN 'BEAR' ELSE 'SIDEWAYS' END lbl,
          COUNT(*), ROUND(100.0*COUNT(*)/SUM(COUNT(*)) OVER (PARTITION BY index_code),1) pct
   FROM scored GROUP BY 1,2 ORDER BY 1,2;   -- breadth 성분은 MV 생성 뒤 mv_stock_market_breadth_daily 조인으로 추가
   ```
4. 실측 2건(키 필요): `AdvisorOpenAiManualTest`(strict 스키마 수용·토큰 — **v2 는 `trendOutlook.kospi.invalidation` 2단계 중첩 객체 수용이 핵심**, 거부되면 평탄화로 후퇴), `KisIndexPriceManualTest`(지수 현재가 TR ID `FHPUP02100000`·필드).
5. 기동 로그 `AI 판단 잡 등록: [ADVISE, SCORE, INTRADAY, WEEKLY_REVIEW, IC_BACKFILL]`, `advisor 설정 확인` 확인.
6. `POST /api/advisor/admin/jobs/IC_BACKFILL` → run 메타 `weights` 검토. `GET /weights` 에서 `flagged`(IC 음수) 시그널 확인 — 부호가 틀린 시그널은 하한 배수 0.5 만 받는다.
   월 청크(`IC:2020-01` …)마다 저장·기록되므로 `GET /runs/{id}` 의 `steps` 로 진행이 보인다. `baseDate` 를 주면 그 날부터만 계산한다(공백 보충용).
   **순서를 건너뛰고 ADVISE 를 먼저 부르면** 증분이 최근 45일(`advisor.ic.incremental-max-days`)만 계산하고 warnings 에 `IC 공백 2020-01-01~…` 을 남긴다 — 판단은 진행되지만 가중치 학습 창이 비어 있으니 IC_BACKFILL 을 이어서 돌린다.
   **`advisor.markets` 를 바꾼 뒤(advice-v5 의 KOSPI 한정 포함)에도 같은 명령을 baseDate 없이 1회** — 저장된 IC 행이 옛 유니버스 기준이라 upsert 로 덮어써야 하며 증분은 이를 건드리지 않는다.
7. `POST /api/advisor/admin/jobs/ADVISE?baseDate=<직전 영업일>` 수동 1회 → Slack 수신·`GET /advices/{id}` 확인.
8. prod `scheduler.advisor-*.enabled: true` 로 재기동.

## 4. 단계적 활성 (기간이 아니라 n 으로)

| 단계 | 진입 조건 | 켜지는 것 |
|---|---|---|
| 1차 | 배포 직후 | 스크리닝(초기 세트) + LLM + Slack + 후보 동결 + 채점 + IC 보고 + QUANT_TOPN 섀도 + 장중 점검 |
| 2차 | 누적 LIVE 픽 ≥ `lesson.min-picks`(300, ≈8주) | 실적 블록·보정 표 주입, 교훈 제안·활성, LLM_NOMEM 섀도 |
| 3차 | IC n_eff ≥ `ic.min-n-eff`(24) | 주간 가중치 세트 자동 갱신 |
| 뉴스 | `GdeltDocManualTest` 실측 → `scheduler.stock-eventfeed` on → 며칠 쌓인 뒤 `advisor.news.enabled` on | news 블록·citedNews·LLM_NONEWS 섀도(8주). **뉴스 가치는 8주 뒤 LIVE − LLM_NONEWS 로만 판정**, se 안이면 다시 off |

전부 yml 임계라 코드 변경 없이 켜진다. **3개월 규칙**: `GET /scores/summary` 의 LIVE 부가가치(픽 − 후보군)가 se 안에서 ≈0 이면 LLM 을 설명 전용으로 내리고 픽은 QUANT_TOPN 으로 전환할 것(사전 결정).

## 5. 첫 2주 관찰 기준

- 추천 5영업일 연속 1통/일, `GET /runs?jobType=ADVISE` 가 SUCCESS, 소요 ≤8분, 입력 토큰 ≤8k(`promptChars`/3), 가드 제거율 <10%(`metadata.guard`).
- 채점 MISSING 0(정지 제외): `GET /advices/{id}` 의 candidateScores.status.
- Slack ≤3통/일, ERROR 0. **적중률·KPI 는 판정하지 않는다**(최소 3~6개월).

```sql
-- run 상태와 단계
SELECT run_id, job_type, status, base_date, llm_calls, prompt_tokens, completion_tokens, round(duration_ms/1000.0) sec,
       metadata_json->'steps' steps, metadata_json->'guard' guard
FROM tb_advisor_run ORDER BY run_id DESC LIMIT 10;
-- 오늘 판단·픽
SELECT a.advice_id, a.variant, a.regime_code, a.kospi_dir, a.p_up, p.pick_rank, p.ticker, p.direction, p.conviction
FROM tb_advisor_advice a JOIN tb_advisor_pick p USING (advice_id) WHERE a.base_date = CURRENT_DATE ORDER BY a.variant, p.pick_rank;
-- 채점 현황
SELECT stage, status, COUNT(*) FROM tb_advisor_candidate_score WHERE horizon_days = 5 GROUP BY 1, 2;
```

## 6. 알림 규칙

| 상황 | 채널 | 멘션 |
|---|---|---|
| 일일 추천 / 아침 점검 / 장중 점검 / 주간 보고 | #hvy-advisor | 없음 |
| run PARTIAL(가드 제거율 >30%, 섀도·발행 실패, 채점 단계 실패) | #hvy-notify | 없음 |
| 잡 예외, 스케줄 트리거 거부(설정 누락·이미 실행 중), 마감 초과 DAILY 미완료 | #hvy-error | 있음 |
| 채팅 봇 WebSocket 끊김/오류(쿨다운 당 1회), Socket Mode 시작 실패 | #hvy-notify | 없음 |
| 채팅 봇 질문 단위 실패·예산 거부 | (경보 없음 — 스레드 답글 한 줄 + ⚠ 리액션, tb_advisor_chat FAILED/SKIPPED) | — |

대응: 트리거 거부 → 원인 해소 후 `POST /jobs/{jobType}`; 마감 초과 → 수집 복구 후 `POST /jobs/ADVISE?baseDate=YYYY-MM-DD`(같은 날 LIVE 가 이미 있으면 SKIPPED → 필요 시 `DELETE /advices/{id}` 후 재실행).

**진행 확인·중단(2026-09-13)**: run 은 단계·IC 청크 경계마다 메타를 저장하므로 `GET /runs/{id}` 의 `metadata.steps`(`IC:2026-08` …)가 실시간으로 늘어난다. 오래 도는 run 은 `POST /api/advisor/admin/runs/{id}/cancel` — run 은 즉시 CANCELED 가 되고 잡은 **다음 단계·청크 경계**에서 멈춘다(알림 없음, 같은 잡 재트리거는 advisorExecutor 가 직렬화). 협조적 취소는 실행 중인 SQL 한 건은 끊지 못하므로 그 경우만 PG 에서 직접 끊는다:
```sql
SELECT pid, now() - query_start AS elapsed, left(query, 120) FROM pg_stat_activity WHERE state <> 'idle' AND query ILIKE '%vw_stock_market_calendar%';
SELECT pg_cancel_backend(<pid>);   -- 끊긴 단계는 FAILED 로 격리되고 잡은 다음 단계로 진행한다
```

## 7. 채점·KPI 규약

- 진입 = 기준일 다음 영업일 수정 시가, 청산 = h번째 영업일 수정 종가(채점 시 `vw_stock_daily_price_adj` 재조회). 벤치마크 = 소속 시장 지수(0001/1001) 같은 규약. β=1.
- 배당락(DIVIDEND, 계수 없음)은 현금배당/진입일 원주가 가산. 비용 0.3% 는 `cost_adj_excess` 보고 전용.
- 청산일 행 없음: 구간 마지막 종가로 청산 — 상폐 DELISTED, 아니면 SUSPENDED. **학습 포함**(빼면 낙관 편향). 진입가 없음 MISSING(다음 날 재시도).
- 잠정(PROVISIONAL) → 마지막 WEEKLY 성공 이후 확정(CONFIRMED) 재채점(유상증자 계수 지연).
- 국면: close-to-close, 밴드 = 0.5×σ_1d×√h(직전 60일; h=5 면 σ_5d — 2026-09-13 이전엔 √5 고정이라 진단 h=1·20 밴드가 틀렸다), 밴드 안 NEUTRAL, Brier 는 부호 기준. 섹터: 업종 지수 있으면 종가, 없으면 MV 동일가중, 시장 대비 초과 >0. **SECTOR 콜은 `leading_sectors[].consistent`(advice-v6) 로 나눠 consistent=true/false 적중률을 따로 본다** — v6 효과는 전환일(첫 v6 LIVE, 2026-09-2x) 전후 SECTOR 적중률·LIVE valueAdd 로 재고, 8주 뒤 n≈120(se≈4.5pp) 에서 분할 차이를 읽는다.
- **아침 점검(advice-v3, h=1 패스만, subject_type `MORNING`)**: predicted=지수별 판정(REINFORCE/HOLD/CAUTION), actual=D+1 시가 갭 `open(D+1)/close(D)−1`, band=임계, actual_dir=|갭|<임계 NEUTRAL 아니면 부호. hit: HOLD 는 |갭|<임계, REINFORCE·CAUTION 은 예상 갭과 부호 일치. 예상 갭이 없던 지수(미국 휴장·β 결손)는 MISSING. 픽 채점은 D+1 시가 진입이라 야간 갭이 픽에는 빠지고 INDEX 콜(close→close)에는 들어간다는 비대칭을 이 행이 설명해 준다(INDEX 를 open(D+1)→close 로 바꾸는 규약 변경은 6개월 뒤 갭 기여도를 본 뒤).
- **추세 전망(advice-v2, h=20 패스만, `tb_advisor_call_score` subject_type)**: `TREND` predicted=persist 버킷, actual_dir=실현 버킷(확정 라벨이 판단 때 동결한 라벨과 처음 달라진 거래일 오프셋 1~5 → WITHIN_5D, 6~20 → ABOUT_20D, 없음 → BEYOND_20D), hit=일치, **Brier=(confidence−1[hit])² 적중 기준(INDEX 의 부호 기준과 섞지 않는다)**, event_date=전환일. `TREND_INV` predicted=무효화 타입, actual_dir=FIRED|QUIET, hit=전환·발동이 둘 다 없거나 둘 다 있고 ±tolerance 안(조기 신호로 작동), NONE 은 MISSING. 정답은 `TrendSql` 로 결정론이라 LLM 선택과 무관하다. **첫 행은 20영업일 뒤, 4주간 적중률을 판정하지 않는다.** TREND 는 픽 진입·청산과 무관한 서술 검증용이며 교훈 evidence·보정 표·가중치에 쓰지 않는다.
- KPI: 변형별(LIVE·QUANT_TOPN·LLM_NOMEM) LONG 픽 승률·평균 초과±se·후보군 평균·**부가가치**, AVOID 별도, 국면 적중·Brier skill, 보정 표, 주간 보고에 "20일 추세 지속 적중 · 무효화 신호 적중" 별도 줄. `data_quality=OK` 만.

## 8. 피드백 규율

- 가중치: `m̂ = 1 + n_eff/(n_eff+24)·(ĪC/0.03 − 1)`, clip[0.5, 2.0], Σ 재정규화, 음의 IC 는 뒤집지 않고 하한+flagged. 세트는 새 행(이력), 픽은 세트 id 참조. 수동 롤백 `POST /weights/sets/{id}/activate`.
- 교훈: 기계 판정 `condition`({regime, trend, signal, op, pct, sector}) + evidence(n≥20, |t|≥2) 필수, 종목코드 금지, 활성 ≤8, 활성 4주/적용 20건 후 (적용 − 비적용) ≤0 이면 폐기, 프롬프트에서는 **확신 조정만**. 생성기엔 누적 셀 집계표·보정 표·활성 교훈 사후 성과만 준다. `trend` 키(lesson-v2)는 스키마·판정에 있지만 생성기의 셀 집계는 아직 regime×시그널×섹터라 trend 조건 교훈은 수동 등록으로만 생긴다(후속). **셀의 t 는 base_date 클러스터 se**(2026-09-21, 사용자 결정 ③): 셀 안 픽을 기준일로 묶은 일별 평균 초과의 표본 표준편차/√D 를 se 로 쓰고 D(서로 다른 기준일 수) < 2 면 t=0 — 같은 날 5픽이 전부 +2% 여도 유의해지지 않는다. `Cell.n` 은 픽 수 그대로, `nDays` 가 생성기 입력에 추가(스키마·`LESSON_VERSION` 불변). 게이트 값(n≥20·|t|≥2·300) 은 그대로고 판정만 보수화된다.
- recentOutcomes(빠른 층, note-v1): 프롬프트에는 **T+5 로 확정된 결정론 빈도표만**(≤5행, 창 20 거래일, `finalized_at·noted_at ≤ 기준일 20:00 KST`, 확정 ≥10 이면), 지시는 확신 **±0.05 한 단계 안**·underpowered(n<30) 참고만·종목 추가/제거/순위 변경 금지. LLM 회고 문장(deviation·why·hypothesis)·OPEN 노트·12:00 원문은 어떤 경로로도 프롬프트에 넣지 않는다(DB·Slack·관리자 기록용). 가드가 recentOutcomes 있는 날 conviction 변화폭을 검사하지는 않는다(원래 확신을 모름) — 대신 NOMEM 대조로 |Δconviction| 을 잰다. 빠른 층은 "규칙 문장" 이 아니라 "관찰 빈도표" 라서 교훈 게이트의 우회가 아니다.
- **사전 등록 판정(NOMEM 8주, 2026-09-21 등록)**: 첫 메모리 주입 LIVE(`memory_json IS NOT NULL` 의 MIN(base_date)) + 8주 뒤 `GET /scores/summary` 변형 표와 주간 보고 `LIVE vs NOMEM` 줄로 판정한다. (a) 픽 Jaccard ≥ 0.9 ∧ 평균 |Δconviction| < 0.05 → 노트가 행동을 바꾸지 않음(inert) → `recentOutcomes` 주입 off(`note.min-finalized` 를 크게 올려 끈다). (b) 짝지은 일별 차 d̄(LIVE − NOMEM 초과) < −1se → 해침 → off. (c) 그 외 → 300 게이트(느린 층 시작)까지 유지, 그때 3요인 분리가 필요하면 `LLM_NONOTES` 4번째 변형을 검토. 8주로는 "바꾸는가·크게 해치는가" 만 답할 수 있음(σ≈2%/5일·n_eff≈N/3 → se≈0.55%/5일, 탐지 가능 효과 ≈1%/5일) — 0.2%/5일 을 t=2 로 보려면 약 1,200일이므로 8주 뒤 "효과 있음" 을 선언하지 않는다. 판정 규칙을 결과를 본 뒤 바꾸지 않는다.
- 재현성: 주 1회 직전 LIVE 입력 동결 재실행, Jaccard <0.7 이면 경고(LLM 랭킹 관여 축소 검토).
- 룩어헤드 불변식: 특징 SQL 은 기준일 이하만(`FeatureSqlTest.noLookahead`, `AdvisorScreeningPgTest.futureRowsDoNotChangeScreening`), LEAD 는 IC·채점에만, 밸류에이션은 당일 스냅샷만(과거 IC 제외), 12:00 정보 소급 금지 — `tb_advisor_pick_note`·`tb_advisor_intraday_check` 를 `FeatureSql`·`SignalIcService`·`AdviceScoringService`·`LessonService`·`CandidateScreeningService`·`MarketFeatureService` 가 참조하지 않음을 `AdvisorSqlBoundaryTest` 가 소스 문자열로 단언한다.

## 9. 확장 훅

- 수집 항목 추가 → `FeatureSql.featureCtes()` feat CTE 컬럼 1줄 + `SignalCode` 상수 1줄 + `advisor-seed.sql` 행 1개(+ `schema-postgres.sql` 미러). 스크리닝·IC·프롬프트가 자동 반영.
- 추세 성분 추가 → `TrendSql.labelCtes()` 의 comp CTE 에 CASE 1줄 + score 합에 항 추가 + `MarketTrendService` components 맵 + `AdvisorProperties.Trend` 손잡이. 판단·채점·기저율이 자동으로 같은 정의를 쓴다.
- 연동 쌍 추가 → yml `advisor.morning.link-pairs` 에 `지수:심볼` 1개(심볼은 `kis.overseas.symbols` 에 수집돼 있어야 함). 코드 변경 없음.
- 유니버스 시장 변경(KOSDAQ 포함 등) → yml `advisor.markets` + `IC_BACKFILL` 재실행. 코드 변경 없음(§1.4).
- 뉴스 소스 추가 → `tb_stock_news.source` 값을 달리해 넣는 수집 잡 1개(`NewsItem` 생성·`StockNewsWriter.upsert`, `serial_no` 는 원천 고유키 해시). advisor 는 source 를 가리지 않는다. DART 공시가 1순위 후보(종목 태그가 있는 유일한 후보). GDELT 테마 추가는 yml `gdelt.themes` 1줄(코드 10자).
- 거시 시리즈 추가 → `MacroSeries` 상수(원천 CSV 열 이름) + yml `macro.series` 1줄. 새 원천이면 `client/macro/MacroCsvSource` 구현 1개(`MacroSourceRouter` 가 자동 등록). 라이브 원천 = 백필 원천·`available_from` 규칙을 지킬 것(§1.5).
- 12:00 노트 class 추가 → `PickNoteClass` 상수 1개(EnumCode, code==상수명) + `PickDeviation.classify` 분기 1줄 + `PickDeviationTest` 경계 케이스 + `advice-system-v6.md` recentOutcomes 문단·`intraday-note-system-v1.md` 의 class 설명 1줄. `note_class` 는 VARCHAR(20) 라 스키마 변경 없음. recentOutcomes 빈도표·Slack 픽 줄은 코드 값을 그대로 쓰므로 자동 반영.
- 2차: 실시간 웹소켓(장중 점검 주기 확대), 백테스트(밸류 이력·상폐 유니버스 스냅샷이 쌓인 뒤). 2026-09-13 프롬프트 v2~v4 계획 원문 `~/.claude/plans/moto-planner-agent-transient-lovelace.md`.

## 10. 미실측·잔여

- `KisIndexPriceManualTest`: 지수 현재가 TR ID 실측(틀리면 rt_cd≠0, 장중 점검 INDEX 실패로 기록).
- `AdvisorOpenAiManualTest`: **Responses `text.format`(json_schema, strict)** 이 strict 스키마(nullable enum 포함, **v2 의 2단계 중첩 trendOutlook**)를 수용하는지·토큰·지연. Chat Completions 로 실측했던 2026-09-13 결과는 API 가 바뀌어 다시 확인해야 한다(§12). 프롬프트 v2 로 `promptChars` 가 v1 보다 약 1,500자 늘어난다(시장 trend 블록 2행 + dataAsOf + window).
- 추세 임계 실측: §3 의 분포 SQL 을 psql 로 돌려 yml `advisor.trend.*` 를 조정한다. breadth 성분은 MV 가 생긴 뒤에야 과거 분포를 볼 수 있다.
- `MacroSourceManualTest`(키 불필요): CBOE·재무부 CSV 형식이 파서와 맞는지, 06:35·08:35 KST 에 `lagDays` 가 1 인지(T-1 확보). 한 주 관찰 뒤 `scheduler.stock-macro` on.
- `GdeltDocManualTest`(키 불필요): 30일 창 버킷 해상도(일/시간)·`timelinevolraw` 의 `norm`·`artlist` 필드명·건수·429 빈도. 2026-09-20 첫 실측은 `timelinetone` 7일 창 시간 버킷만 확인했고 나머지는 429·빈 응답 `{}` 이었다. 통과 뒤 `scheduler.stock-eventfeed` on → 며칠 쌓인 뒤 `advisor.news.enabled` on(뉴스 주입은 별도 결정).
- `KisOverseasSymbolManualTest`(미작성): VIX·미국 10년물 심볼을 KIS 가 주는지. 주면 `MacroCsvSource` 대신 `kis.overseas.symbols` 1줄로 대체 가능(저장은 `tb_stock_macro_daily` 유지).
- psql 적용 순서(v2~v4 한 번에): `db/stock-schema.sql`(tb_stock_news) → `cat db/stock-derived-rebuild.sql db/stock-derived.sql | psql -1`(breadth MV) → `db/advisor-schema.sql`(13번째 테이블·ALTER 블록) → `db/advisor-seed.sql`. **2026-09-20 추가분**: `db/stock-schema.sql` 재적용(`tb_stock_macro_daily`·`tb_stock_event_timeline`·`uk_stock_news_serial` — 전부 IF NOT EXISTS) → 확인 `SELECT COUNT(*) FROM information_schema.tables WHERE table_name LIKE 'tb_stock_%'` = 25 → 선택 `DELETE FROM tb_stock_news WHERE source = 'KIS'` → 배포 → `POST /api/stock/admin/collect/MACRO {"startDate":"2015-01-01"}`(202, 시리즈 3 × 연도 파일) → `POST /NEWS {"startDate":"2017-01-01"}`(202, 6테마 × 90일 청크 × 2모드 ≈ 480호출 × 6초 ≈ 50분).
- 섹터 채점의 업종 지수 코드(`sector_code` ↔ `tb_stock_index_daily.index_code`) 동일성 — 불일치면 MV 폴백이 자동 적용. **advice-v6 는 같은 동일성에 rs/mom/consistent 가 걸린다** — 아래 매칭률 SQL 로 선행 확인.
- ~~관리자 화면 없음~~ → 2026-09-20 blog-nextjs `/admin/quant/advisor` 로 해소(§13). 백엔드는 `GET /gate`·runs 필터만 추가(additive). 노트 탭은 후속(API 는 §13).
- **advice-v6 · note-v1 배포 절차(2026-09-21)**:
  1. psql `db/migrate/20260921_01_advisor_note_v1.sql`(이번 릴리스 증분 한 파일: pick_note CREATE·memory_json ALTER·주석·SECTOR_MOM 시드 2행, 재실행 안전) — 또는 전체 `db/advisor-schema.sql` 재적용(`tb_advisor_pick_note` CREATE·`tb_advisor_advice.memory_json` ALTER 블록 — 전부 IF NOT EXISTS) → `db/advisor-seed.sql`(`SECTOR_MOM_20D`·`SECTOR_MOM_60D` 2행, ON CONFLICT DO NOTHING). 확인 `SELECT COUNT(*) FROM information_schema.tables WHERE table_name LIKE 'tb_advisor_%'` → 15.
  2. 선행 확인 SQL 3개(업종 지수가 없으면 rs·mom 전부 null → consistent 전부 false → 규칙 8 폴백만 돈다):
     ```sql
     -- 업종 지수 적재 (3 초과여야 함 — 0001·1001·2001 만 있으면 업종 일봉이 없다)
     SELECT COUNT(DISTINCT index_code) FROM tb_stock_index_daily;
     -- 섹터 코드 ↔ 업종 지수 매칭률 (0.8 미만이면 매핑 테이블 선행 후 진행)
     SELECT COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM tb_stock_index_daily i WHERE i.index_code = sm.sector_code))::float / COUNT(*)
     FROM tb_stock_sector_map sm WHERE sm.source = 'KRX' AND sm.valid_to IS NULL;
     -- 업종 지수 최신일 (국내 종가 dataAsOf 와 같아야 rs 가 non-null — 다르면 dataAsOf.sectorIndex 로 드러난다)
     SELECT MAX(trade_date) FROM mv_stock_index_metric
     WHERE index_code IN (SELECT DISTINCT sector_code FROM tb_stock_sector_map WHERE valid_to IS NULL);
     ```
     업종 일봉이 없으면 `POST /api/stock/admin/collect/INDEX_BACKFILL` 먼저.
  3. 배포 → `POST /api/advisor/admin/jobs/IC_BACKFILL`(baseDate 없이) 1회 — 새 시그널 IC 사전 추정·새 BACKFILL 세트 활성화. 그 전엔 `SECTOR_MOM_*` 가 점수에서 빠진다(프롬프트 특징 `secRs60/secCons` 는 즉시).
  4. 다음 19:30 ADVISE: `GET /advices/{id}/prompt` 에서 sectors 컬럼 13개·candidates `secRs60/secCons`·`recentOutcomes` 부재(확정 노트 <10) 확인, 가드 stats `capNonConsistent` 비율(50% 넘으면 프롬프트가 규칙을 안 따르는 신호). **전환일(첫 v6 LIVE) 기록.** 다음 12:00 INTRADAY: `tb_advisor_pick_note` N행·class 분포·Slack 픽 줄·`tb_advisor_run.llm_calls=1`(전부 FLAT 이면 0). 장외 수동 실행은 현재가=종가라 `offHours=true`·"장외 점검" 표기.
  5. 5영업일 뒤 SCORE → 노트 CONFIRMED/REFUTED·`finalized_at` → 확정 ≥10 이후 ADVISE 프롬프트에 `recentOutcomes` + `memory_json` + `SHADOW_NOMEM` 단계(LLM 2회). 8주 뒤 §8 사전 등록 판정.
  6. 실측 `KisIndexPriceManualTest`(키 필요): 지수 현재가 응답에 `bstp_nmix_oprc`(업종 지수 시가) 가 채워지는지 — 없으면 excess 가 PREV_CLOSE 폴백으로만 계산된다(run 메타 `excessBasis`).
  7. 롤백: `PromptResources.ADVICE_VERSION` 을 v5 로 되돌리고 재배포(테이블·컬럼은 남겨도 무해). 노트 회고만 끄려면 `ADVISOR_NOTE_ENABLED=false`.

## 11. Slack 채팅 봇 (chat-v1, 2026-09-13)

`#hvy-advisor` 에 **허용된 사용자**가 새 글이나 댓글로 물으면 봇이 같은 스레드에 답한다. 답은 LLM 이 쓰되 **숫자는 전부 도구가 DB 에서 읽은 값**이고, 도구가 없는 질문은 "해당 데이터가 없다" 고 답한다(임의 SQL 도구 없음 — 룩어헤드·원주가 직접 읽기를 막을 수 없기 때문). 코드는 `modules/advisor/application/chat`(+`chat/tool`), 감사 테이블 `tb_advisor_chat`.

### 11.1 흐름

Socket Mode(bolt-socket-mode + Java-WebSocket, 공개 URL·서명 검증 없음) → `SlackChatRouter` 필터(채널 → 봇 → 사용자 → 자기 자신 → 허용 목록 → 본문 → Redis event_id 중복) → **항상 즉시 ack** → `advisorChatExecutor`(2스레드·큐 10) → `AdvisorChatService`: 감사 RUNNING → 👀 → 예산·쿨다운(`ChatBudgetGuard`) → `AdvisorChatClient`(시스템 프롬프트 `prompts/advisor/chat-system-v1.md` + 스레드 히스토리 + 도구 14종 루프) → mrkdwn 답글(3,000자 분할·메타 context·고정 면책) → SUCCESS → ✅. 실패는 스레드 한 줄 + FAILED + ⚠ 까지이고 경보는 없다(질문자가 스레드에서 본다). 핸들러 없는 subtype 이벤트(수정·삭제·파일)는 자동 ack 로 버린다.

스레드 히스토리는 `conversations.replies` 로 읽어 봇의 일일 판단 메시지는 **Block Kit 을 평문으로 펼쳐** 맥락으로 넣는다(text 는 알림용 요약뿐). 루트 헤더의 기준일을 뽑아 `latestAdvice(baseDate)` 힌트로 준다. `tb_advisor_advice.slack_ts` 저장은 hvy-common 변경(응답 ts 반환)이 필요해 범위 밖.

**LLM 호출 경로(2026-09-19, Responses API).** GPT-5.4 이상은 Chat Completions 에서 도구 호출 시 `reasoning_effort=none` 만 허용해(`400: Function tools with reasoning_effort are not supported … use /v1/responses`) 도구 14종을 붙이는 채팅만 죽었다(judge/assist 는 도구가 없어 무관). Spring AI 2.0.1 에는 Responses 용 ChatModel 이 없어 `modules/advisor/client/openai/OpenAiResponsesChatModel`(커스텀 `ChatModel`, `call`+`getOptions` 만 구현, **도구는 실행하지 않음** — 루프는 그대로 `ToolCallingAdvisor`) 을 `chatChatClient` 에 끼웠다. `ChatClient`·`ToolCallingManager` 상한·`.tools(툴킷 4종)`·`ToolContext`·`AdvisorChatClient` 는 무변경. 같은 날 judge/assist 도 이 모델로 옮겨 SDK 를 걷어냈다(§12).
- 무상태 세션: `store=false` + (도구가 있을 때만) `include=[reasoning.encrypted_content]`, 응답 `output` 원문(reasoning 암호화 블롭·function_call)을 `AssistantMessage` 메타데이터(`openai.responses.output`)에 실어 다음 라운드 `input` 에 `status` 만 빼고 되돌려 보낸다. `ToolResponseMessage` → `function_call_output(call_id)`.
- HTTP 는 `openAiRestClient`(`RestClientConfig`, `RestClientConfigurer.restClient` 헬퍼) → **호출 1건 = `tb_api_log` 1행**(요청·응답 전문, traceId, 소요, 본문 1 MiB 상한, 60일 보존). 질문 1건에 1+도구 호출 수 행(≤13). 재시도(429·408·409·5xx·네트워크, Retry-After ≤30s, 지수 백오프, `advisor.model.max-retries`)는 `OpenAiResponsesClient` 가 직접 하므로 재시도도 각각 1행. `Authorization` 은 `OpenAiBearerAuthInterceptor` 가 로그 인터셉터 **뒤에서 헤더 사본에만** 넣어 `request_header` 에 키가 남지 않는다(인터셉터 순서 계약, `OpenAiBearerAuthInterceptorTest`). 로컬 `default` 프로필은 `ApiLogService` 가 없어 콘솔 전문 출력만.
- 도구 스키마는 Spring AI 생성 `inputSchema` 에서 루트 `$schema` 만 제거, `strict=false`(optional 파라미터가 `required` 에 없어 strict 규칙과 안 맞음). 도구 결과가 `max-total-tool-calls` 이상 쌓이면 `tool_choice=none` 으로 답을 강제(상한 초과 뒤 무한 루프 방지). `status=incomplete(max_output_tokens)` 는 WARN + 있는 텍스트만(finishReason `LENGTH`, 다른 사유는 `INCOMPLETE`), `refusal` 파트만 오면 거부 문구가 답으로(`REFUSAL`), `failed` 는 예외 → Slack 실패 한 줄. 추론 토큰은 Spring AI 의 라운드 합산이 native usage 를 버리므로 메타데이터 누적값(`openai.responses.usage.cumulative`)으로 `tb_advisor_chat.reasoning_tokens` 에 넣는다(캐시 토큰은 `cacheReadInputTokens` 슬롯으로 합산).
- 후속: 도구 strict 스키마(모든 속성 required + nullable)·hvy-common `ApiLogInterceptor` 헤더 마스킹은 별도.

### 11.2 도구 14종 (`chat/tool`)

| toolkit | 도구 | 원천 |
|---|---|---|
| Market | `marketOverview` `marketTrend` `globalLink` | MarketFeatureService · MarketTrendService · GlobalLinkService |
| Stock | `resolveStock` `stockSnapshot` `priceSeries` `metricTopN` `newsHeadlines` | StockLookupReader(신설 SQL 3개) · DerivedViewRefresher.adjustedCloses · StockNewsWriter |
| Advice | `latestAdvice` `adviceChecks` `screeningTop` `performanceSummary` | AdviceWriter · Score/Morning/IntradayCheckWriter · CandidateScreeningService · AdvisorKpiService |
| Calendar | `dataFreshness` `tradingDays` | 지표 MAX(trade_date) · MarketFeatures.dataAsOf · TradingCalendar |

공통(`ToolSupport`): 읽기 전용 트랜잭션 + `SET LOCAL statement_timeout`, 예외는 `{"error":…}` 로(예외가 새면 도구 루프가 죽어 무응답), **기준일은 지표 테이블의 실제 마지막 거래일로 클램프**(미래·미수집 날짜 행은 존재하지 않는다 = 룩어헤드 불변식, `AdvisorChatToolPgTest` 가 14종을 미래 날짜로 검증), 도구 호출 이름·참조 기준일은 `ToolContext` 의 `ChatRequestScope` 에 기록 → `tb_advisor_chat.tool_calls_json`·`data_as_of`. `metricTopN` 의 정렬 컬럼은 `MetricColumn` enum 만 SQL 에 보간된다.

### 11.3 Slack 앱 설정 (수동, 배포 전)

**기존 앱에 Socket Mode 를 얹는다 — 새 앱을 만들면 bot 토큰이 바뀌어 기존 알림 4채널이 끊긴다.**
1. api.slack.com/apps → 기존 앱 → **Socket Mode** Enable on
2. Basic Information → App-Level Tokens → Generate, scope `connections:write` 하나만 → `xapp-1-…` 을 `SLACK_APP_TOKEN` (화면을 벗어나면 다시 못 본다). **토큰은 하나만, 운영 서버 `prod.env` 에만 둔다**(아래 "연결은 앱 단위" 참고)
3. OAuth & Permissions → Bot Token Scopes: `chat:write`(있음), `groups:history`(`#hvy-advisor` 는 **비공개 채널**이다. 공개 채널이면 `channels:history`), `reactions:write`
4. Event Subscriptions → Enable → bot events `message.groups`(공개 채널이면 `message.channels`). Socket Mode 라 Request URL 란은 없다
5. **Reinstall to Workspace**(스코프 변경 시 필수, bot 토큰 값 유지) → `#hvy-advisor` 에서 `/invite @봇`
6. 채널 ID(채널 세부정보 맨 아래 `C…`) → `ADVISOR_CHAT_CHANNEL_ID`, 내 사용자 ID(프로필 ⋮ → Copy member ID `U…`) → `ADVISOR_CHAT_ALLOWED_USERS`
- 넣지 않는 스코프: `app_mentions:read`(멘션 전용 아님), `users:read`(ID 로 관리), `im:history`(DM 범위 밖). `channels:history` 는 초대된 공개 채널의 모든 메시지를 받으므로 **봇을 다른 채널에 초대하지 않는다**(필터 1단계가 방어).
- 매니페스트에 `features.bot_user.always_online: true` 를 두면 연결이 살아 있는 동안 봇이 온라인(초록 점)으로 보인다 — 연결 상태를 보는 가장 싼 방법.
- **Socket Mode 연결은 Slack 앱 단위로 묶인다.** 앱당 최대 10 연결이 허용되고 Slack 은 이벤트를 **열린 연결 중 하나에만** 보낸다. 같은 App-Level Token 으로 다른 프로세스(다른 기기의 로컬 실행 등)가 붙어 있으면 질문이 그쪽으로 새어 blogback 에는 로그 한 줄 없이 무응답이 된다(2026-09-17 실제 발생 — hello `num_connections: 4`). 폰·PC 의 Slack 클라이언트는 이 수에 들지 않는다. 기동 로그 `advisor chat Socket Mode hello: connections=1` 을 확인하고, 2 이상(WARN + 기동 시 `#hvy-notify` 경보)이면 App-Level Token 을 **전부 Revoke → 재발급**해 `prod.env` 에만 넣는다. 옛 연결이 남으면 Socket Mode 토글 Off→On(전 연결에 `link_disabled`)하거나 연결 수명(~5h)을 기다린다. 로컬 `.env` 에는 `SLACK_APP_TOKEN` 을 두지 않는다.

### 11.4 배포 절차 (순서: psql → env → 재생성 → Slack 앱 → 첫 질문)

1. psql `db/advisor-schema.sql` 재적용(`tb_advisor_chat` 은 `CREATE TABLE IF NOT EXISTS`). 확인 `SELECT COUNT(*) FROM information_schema.tables WHERE table_name LIKE 'tb_advisor_%';` → 14
2. env(`docker-compose/blog/back/prod.env`): `SLACK_APP_TOKEN`, `ADVISOR_CHAT_CHANNEL_ID`, `ADVISOR_CHAT_ALLOWED_USERS`(+ 선택 `ADVISOR_CHAT_MODEL`, `ADVISOR_CHAT_REASONING_EFFORT`(비면 서버 기본), `ADVISOR_CHAT_ENABLED` 는 prod 기본 true). 컨테이너는 `recreate.sh`(compose `up -d --force-recreate`)로 재생성 — `restart` 는 env 미반영
3. 기동 로그 `advisor chat 활성: channel=…` · `advisor chat Socket Mode 연결 시작` · **`advisor chat Socket Mode hello: connections=1`**. WARN `advisor chat 설정 누락 [SLACK_APP_TOKEN, …]` 이면 그 env 가 빈 것. Slack 사이드바 봇 초록 점
4. 첫 질문 5개와 기대 도구(답글 꼬리 `tools:` 와 대조): "삼성전자 최근 흐름 어때?" → resolveStock→stockSnapshot(+priceSeries) · "20일 모멘텀 상위 10개" → metricTopN(ret_20d) · (판단 메시지 댓글) "오늘 판단 근거 다시 설명해줘" → latestAdvice · "코스피 지금 강세장이야?" → marketTrend · "SOX 랑 코스닥 베타 얼마야?" → globalLink
5. **반증 3개(필수)**: "삼성전자 올해 영업이익 얼마야?" → "해당 데이터가 없습니다"(숫자가 나오면 프롬프트 실패, 운영 불가) · "내일 코스피 오를까?" → 조건부 시나리오(목표가·확률 단정이면 실패) · 비허용 계정 질문 → 무응답
6. 롤백 `ADVISOR_CHAT_ENABLED=false` + 재생성. Slack 앱 설정은 그대로 둬도 무해.

```sql
-- 최근 대화
SELECT chat_id, status, slack_user_id, left(question, 40) q, tool_calls, prompt_tokens, completion_tokens, cost_usd, duration_ms, data_as_of, error_message
FROM tb_advisor_chat ORDER BY created_at DESC LIMIT 10;
-- 오늘 토큰·비용 (KST)
SELECT COUNT(*) n, SUM(prompt_tokens) in_tok, SUM(completion_tokens) out_tok, SUM(cost_usd) usd
FROM tb_advisor_chat WHERE created_at >= (date_trunc('day', NOW() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul');
-- 도구 분포
SELECT t, COUNT(*) FROM tb_advisor_chat, jsonb_array_elements_text(tool_calls_json) t GROUP BY 1 ORDER BY 2 DESC;
-- LLM 호출 전문 (Responses API, 질문 1건 = 1+도구 호출 수 행). trace_id 로 tb_advisor_chat 와 같은 요청을 잇는다. key_leaked 는 항상 false 여야 한다
SELECT created_at, response_status, process_time, length(request_body) req, length(response_body) res, trace_id,
       position('Authorization' in request_header) > 0 AS key_leaked
FROM tb_api_log WHERE request_uri LIKE '%/v1/responses%' ORDER BY created_at DESC LIMIT 20;
```
관리자 `GET /api/advisor/admin/chats?limit=50` 도 같은 내용. 모델에게 무엇을 보냈고 도구가 무엇을 돌려줬는지는 `/admin` API 로그 화면에서 `/v1/responses` 행의 `request_body`(instructions·히스토리·function_call_output)·`response_body`(function_call 인자·답)로 본다.

### 11.5 관찰·조정

- `#hvy-error` 에 알림이 오면 봇 경로에서 예외가 샌 것(실패 처리 계약 위반). traceId 는 `advisorChatExecutor` 가 ThreadPoolTaskExecutor 라 자동 부착.
- 질문당 `prompt_tokens`(히스토리+도구 결과 지배적 → 크면 `tool-row-limit`·`priceSeries` days 축소) · `tool_calls` 평균 3~4 초과면 도구 설명문 모호 · `cached_tokens` 0 지속이면 시스템 프롬프트가 매번 달라지는 것 · `duration_ms` p95 가 180초 근접이면 `max-total-tool-calls` 축소 · `history_messages` 30 상한 발동 여부. 며칠 뒤 `daily-token-budget` 300,000 을 실측으로 교체.
- Socket Mode 는 끊긴 동안 온 메시지를 Slack 이 재전송하지 않아 **유실**된다(개인 규모라 수용). 👀 리액션이 안 붙으면 봇이 못 받은 것이니 다시 묻는다(`reactions:write` 누락이면 WARN `reactions.add 실패` 가 남는다). SDK 가 자동 재연결 — 약 5시간마다 Slack 이 연결을 refresh 시키며 이때 옛 세션의 close 1000 은 INFO 로만 남는다(비정상 코드·error 만 WARN + 경보).
- **무응답 진단 순서**(Loki `{container="blogback"}`): ① `|= "Socket Mode hello"` 의 `connections` 가 1 인가(2 이상이면 위 "연결은 앱 단위") → ② 질문 뒤 `|= "Socket Mode Request" |= "events_api"` 가 오는가(SDK DEBUG 필요 — 밑줄 패키지라 `SPRING_APPLICATION_JSON={"logging.level.com.slack.api.socket_mode":"DEBUG"}`) → ③ `|= "Unsuccessful Bolt app execution"`(Bolt 미들웨어 거부) → ④ `|= "Slack 메시지 무시"`(라우터 필터, 대상 채널 안의 폐기는 INFO) → ⑤ `tb_advisor_chat`. ①~② 가 막히면 Slack 이 안 보내는 것이라 앱 코드·로그로는 원인이 보이지 않는다.
- 프롬프트 수정 = `chat-system-v1.md` 교체 + `PromptResources.CHAT_VERSION` bump(`tb_advisor_chat.prompt_version` 으로 전후 분리). 도구 설명문 변경도 같은 규약.
- 범위 밖·후속: hvy-common `thread_ts`/ts 반환(→ 판단 스레드 자동 후속), 임의 SQL 도구, 사용자별 장기 기억, 스트리밍, DM·다중 채널, 정형 도구 결과 직접 표 렌더링(`SlackWidth`), 👍/👎 리액션 수집.

## 12. OpenAI 호출 경로 통합 — Responses API 전부, SDK 없음 (2026-09-19)

judge/assist 도 채팅 봇과 같은 `OpenAiResponsesChatModel` 로 옮겼다. advisor 의 OpenAI 호출은 이제 **전부 `POST /v1/responses` + `openAiRestClient`** 라 `tb_api_log` 에 남고(`SELECT … FROM tb_api_log WHERE url LIKE '%/v1/responses%'`), `spring-ai-starter-model-openai`·`spring-ai-openai`·공식 SDK(`openai-java-core`)·그 전이(Jackson 2 databind·kotlin-reflect·Boot restclient/webclient 스타터·Spring AI 자동구성)는 의존성에서 빠졌다. build.gradle 은 `spring-ai-client-chat` 만 남는다(ChatClient·ToolCallingAdvisor·`@Tool`). `okhttp`/`kotlin-stdlib` 은 Slack SDK 전이로 남는다.

- **구조화 출력**: `ResponsesChatOptions.textFormat` → 요청 `text.format = {type: json_schema, name: 응답 레코드 클래스명(AdviceResponse·LessonProposalResponse), strict: true, schema}`. 이름 규칙(`^[a-zA-Z0-9_-]{1,64}$`)·객체 스키마는 `ResponsesTextFormat` 생성 시 검증. 스키마 자체(`AdviceSchemaFactory`·`LessonSchemaFactory`, 모든 속성 required + additionalProperties=false + 후보 enum 주입)는 무변경.
- **옵션 타입**: Spring AI `OpenAiChatOptions` 대신 자체 `ResponsesChatOptions`(코어 `DefaultToolCallingChatOptions` 상속 + reasoning.effort·tool_choice·parallel_tool_calls·text.format). `maxTokens` 가 `max_output_tokens`(yml 키 `*-max-completion-tokens` 는 env 호환을 위해 이름 유지). 도구가 없으면 `tools`·`tool_choice`·`parallel_tool_calls`·`include` 를 보내지 않는다(judge 의 큰 추론 블롭이 api_log 본문을 부풀리지 않게).
- **ChatModel 3빈(judge/assist/chat) + HTTP 클라이언트 1빈** — `AdvisorAiConfig.JUDGE_MODEL/ASSIST_MODEL/CHAT_MODEL`. 이유: Spring AI 2.0.1 ChatClient 는 요청 옵션을 "**ChatModel 기본 옵션** + 요청 customizer" 로 만들고, `ChatClient.Builder.defaultOptions(...)` 는 그 customizer 일 뿐이라 요청의 `.options(...)` 가 **통째로 교체**한다. 이전 구조(ChatModel 1개 공유 + ChatClient defaultOptions 로 judge/assist 분리)에서는 `MarketJudgeClient` 가 responseFormat 을 `.options()` 로 넘길 때 assist 의 model·상한이 버려져 **교훈 제안(WEEKLY_REVIEW, assist 빈)도 judge 모델·judge 상한으로 나갔다**(증상: `tb_advisor_run.model` 이 judge). 기본 옵션을 역할별 모델에 두면 어떤 customizer 를 얹어도 base 가 산다. 회귀 방어: `ResponsesChatOptionsTest`(병합 계약)·`MarketJudgeClientTest`(가짜 HTTP 위 진짜 모델로 요청 캡처)·`AdvisorContextBootTest.WithApiKey`(컨텍스트에서 judge/assist 빈을 실제 호출해 model·max_output_tokens 대조).
- **완료 신호**: `MarketJudgeClient` 는 JSON 파싱 전에 finishReason 을 본다 — `LENGTH`(max_output_tokens 절단) → "상한 확인" 예외, `INCOMPLETE`(content_filter 등, 사유는 응답 메타 `openai.responses.incompleteReason`) → 사유 포함 예외, `REFUSAL` → 거부 문구 포함 예외. 전부 잡 FAILED(부분 추천 금지). 채팅 경로는 같은 신호를 텍스트로 사용자에게 보인다.
- **배포 후 확인**: 첫 ADVISE 뒤 `tb_api_log` 에 `/v1/responses` 행(judge 1건, 뉴스 on 이면 섀도 포함 2~3건), `tb_advisor_advice.model` = judge 모델. 첫 WEEKLY_REVIEW 뒤 `tb_advisor_run.model` 이 **assist 모델**로 찍히는지(REPRO 가 켜져 있으면 마지막 호출인 judge 로 덮이므로 `tb_advisor_prompt_input.options_json.model` 로 본다). `AdvisorOpenAiManualTest` 로 strict 중첩 스키마 수용 실측(§10).

## 13. 관리자 화면 (blog-nextjs `/admin/quant/advisor`, 2026-09-20)

§10 의 "관리자 화면 없음" 이 해소된다. 화면은 기존 `/api/advisor/admin/**` 를 그대로 호출하고 백엔드는 아래 2건만 늘었다(stock 쪽·스케줄러 매핑은 stock-collect.md §13).

- **화면**: `/admin/quant`(운영 현황 — AI 판단 게이트 칩 + "지금 판단 실행"), `/admin/quant/advisor`(실행 이력 · 판단 이력 · KPI(LLM 사용량·누적 LIVE 픽/300) · 가중치·교훈). `advisor.enabled=false` 환경은 컨트롤러 자체가 없어 404 → 화면은 위젯 단위로 "비활성" 빈 상태를 보이고 페이지는 깨지지 않는다.
- **스케줄러 "지금 실행" 매핑**(`SchedulerStatus.manualTrigger`, 정본 `SchedulerCatalog`): advisor-advise → `ADVISE`, advisor-intraday → `INTRADAY`, advisor-morning-check → `MORNING_CHECK`, advisor-weekly-review → `WEEKLY_REVIEW`. `POST /jobs/{jobType}` 를 baseDate 없이(오늘) 부른다 — 스케줄러와 같다. ADVISE 만 실행 전 `GET /gate` 판정을 보여주고 `ready=false` 면 "SKIPPED 로 닫힘" 을 예고한다(`pastDeadline` 이면 잡이 `#hvy-error` 알림도 보낸다, §6). SCORE·IC_BACKFILL 은 스케줄러가 없어 수동 전용.
- **재시도의 의미** — 별도 API 없음. `POST /jobs/{jobType}?baseDate=` 로 같은 날짜를 다시 돌린다. 종료 run 의 "재실행" 은 **원 run 의 `metadata.requested` 를 재현한다**: `requested=false`(스케줄 run, 또는 baseDate 없이 API) 이고 `baseDate` 가 오늘이면 baseDate 를 생략, 그 외에는 `?baseDate={run.baseDate}`. 이유: `AdvisorOrchestrator` 가 `requested = baseDate != null` 을 메타에 남기고 IC_BACKFILL 이 시작일 결정에 쓰므로, 스케줄 run 을 baseDate 붙여 재실행하면 다른 잡이 된다. ADVISE 재판단은 LIVE 판단이 있으면 게이트가 SKIPPED 로 닫으므로 `DELETE /advices/{id}` 뒤에 한다(§6·§10 규칙 그대로) — 삭제는 후보·픽·채점·장중/아침 점검을 CASCADE 로 지우지만 `tb_advisor_signal_ic_daily` 는 남으므로 재판단 뒤 IC_BACKFILL 이 필요하다.
- **신설 엔드포인트(additive)**
  - `GET /api/advisor/admin/gate?baseDate=` → `AdvisorGateResponse{baseDate, tradingDay, alreadyDone, dataReady, pastDeadline, quality, reason, ready, waitQuietly}`. `AdvisorGateService.decide` 와 같은 판정(스케줄러·AdviseJob 이 쓰는 것)이고 `ready`·`waitQuietly` 는 서버가 **값으로** 확정한다(record 파생 메서드는 Jackson 이 직렬화하지 않고, 프론트가 다시 계산하면 규칙이 두 곳에 생긴다). `reason` 은 게이트의 한글 사유 그대로("휴장일 …", "이미 판단이 있습니다", "파생 지표가 아직 없습니다", "DAILY 수집이 아직 끝나지 않았습니다", "DAILY 의 PRICE·DERIVED 단계가 실패했습니다: run=…", "DAILY 완료"). 부작용 없음.
  - `GET /api/advisor/admin/runs?jobType=&status=&from=&to=&limit=` — `status`(`AdvisorStatus`)·기간(`startedAt` 기준 KST 날짜 양끝 포함) 선택 필터. stock 과 동형(`Specification`, null 조건은 Predicate 미생성).
  - **note-v1(2026-09-21, additive)**: `GET /api/advisor/admin/notes?from=&to=&status=&limit=` — 12:00 픽 노트(기준일 양끝 포함, `PickNoteStatus` OPEN|CONFIRMED|REFUTED, 최신 점검 순). `GET /advices/{id}` 응답에 `notes`(그 판단의 노트 전부, 점검 시각·티커 순)와 헤더 `memoryJson`(프롬프트에 실린 메모리 요약, 없으면 null) 이 추가됐다. 화면(노트 탭)은 후속.
- 검증: `AdvisorAdminGateIntegrationTest`(advisor.enabled=true + Redis Testcontainers, 게이트는 목으로 두고 HTTP 계약·펼침·runs 필터를 본다 — H2 에 advisor JDBC 테이블이 없어 실제 판정은 `AdvisorGateServiceTest` 가 담당).
