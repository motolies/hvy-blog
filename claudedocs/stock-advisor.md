# AI 시장 판단(advisor) 운영 문서

- 모듈 `kr.hvy.blog.modules.advisor`, 테이블 `tb_advisor_*` 13개(`db/advisor-schema.sql`, 시드 `db/advisor-seed.sql`; 13번째 `tb_advisor_morning_check` 는 advice-v3), REST `/api/advisor/admin/**`(ROLE_ADMIN)
- 작성 2026-09-13. 계획 원문 `~/.claude/plans/elegant-singing-glade.md`. 수집 계층은 `claudedocs/stock-collect.md`.
- **투자 자문이 아니다.** 개인 실험이며 모든 Slack 메시지에 면책 문구가 고정된다.

## 1. 한 바퀴 (선순환)

| 시각(KST) | 잡 | 내용 |
|---|---|---|
| 18:30 평일 | stock DAILY | 일봉·지표 수집 (advisor 의 입력) |
| 19:30~19:55 5분 간격 | **ADVISE** | 게이트(DAILY 완료·PRICE/DERIVED OK) → 채점·IC 증분 → 시장 특징(지수·수급·해외·섹터·σ + **규칙 추세·관측 기준일·적용 구간**) → 정량 스크리닝(KOSPI 유니버스 `advisor.markets` ≈ 수백 → 컷 → 후보 30, 섹터당 ≤4) → 정량 top-N 섀도 → LLM 판단(strict JSON, 후보 enum) → 가드 → 저장·입력 스냅샷 → **#hvy-advisor 발행** → (메모리 활성 시) 메모리 없는 LLM 섀도 |
| 07:30 평일 | **MORNING_CHECK** | 06:30 해외 수집 뒤·09:00 개장 전. 밤사이 미국 마감 수익률 × 기준일 β(주 심볼)로 **예상 갭**을 계산해 직전 판단의 지수 방향을 유지/강화/주의 판정(규칙 기반, LLM 없음, 원 판단 불변). `tb_advisor_morning_check` 1행 + Slack 짧은 보고. h=1 채점에서 D+1 시가 갭과 대조(`MORNING`) |
| 12:00 평일 | **INTRADAY** | 직전 영업일 판단을 KIS 현재가로 대조, 일치율·판정 짧은 보고(규칙 기반, 학습 미반영) |
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

### 1.3 뉴스 입력 (advice-v4, 2026-09-13) — 기본 off

비정형 입력은 LLM 의 진짜 우위이지만 **백테스트가 불가능**하므로(KIS 제목 API 는 실시간 조회) 가치는 섀도로만 잰다.

- 수집(stock 모듈, `NEWS` 잡, `scheduler.stock-news` 평일 08:05~19:35 30분): KIS 종합 시황/공시(제목) 전체 피드를 최신순으로 받아(응답 헤더 `tr_cont=M` 인 동안 같은 파라미터로 재호출, 공식 예제와 동일) `tb_stock_news` 에 넣는다. 제목·작성 시각·관련 종목코드(iscd1~5)만 있고 본문은 없다. 중복 키 = (source, 정규화 제목 sha256, published_at). **경로·TR ID `FHKST01011800`·응답 필드는 2026-09-13 운영 실측으로 확정. 요청 필터(제공사·시장·정렬·날짜·시각·일련번호)는 KIS 공식 확인 스크립트(`chk_news_title.py`)처럼 전부 공백으로 보낸다 — 제공사 `0`·정렬 `01`·날짜/시각=지금 을 보냈던 첫 운영 실행은 열흘 넘게 오래된 40행만 받아 0건이었다.** run 메타 `fetched → candidates → inserted`(+`skippedOld`·`unparsed`)와 받은 행의 작성 시각 경계 `newest/oldest` 로 깔때기를 본다. 받았는데 전부 못 쓰면 WARN. "실시간" 은 하루 1회 19:30 판단에 가치가 없어 30분이면 충분하다.
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

### 1.2 미국 연동 (advice-v3, 2026-09-13)

19:30 판단 시점의 미국 데이터는 **T-1 현지일 마감**이며 이미 국내 종가에 반영된 과거다(미국 당일 세션은 22:30 개장). 그래서 판단 입력에는 **연동 강도만** 넣고, 미국 정보가 전방인 유일한 구간인 **07:30 아침 점검**에서 예측 가치를 취한다.

- 입력 `market.link[{kr, us, beta, corr, n}]`(`GlobalLinkService`): 쌍은 yml `advisor.morning.link-pairs`(기본 KOSPI:SPX·SOX, KOSDAQ:COMP·SOX), 창 60 국내 거래일. **정렬**: 국내 d일 수익률 ↔ 현지일 ∈ [국내 직전 거래일, d−1] 인 미국 세션(월요일 ↔ 금요일). 그 구간에 미국 세션이 없으면(미국 휴장) 짝을 짓지 않는다 — 같은 미국 수익률을 두 국내일에 재사용하지 않기 위해서다. `market.global` 에 r20/r60 이 붙었고, 조회 필터가 `현지일 < 기준일` 로 바뀌어 사후 재실행(`baseDate=`)에서도 밤사이 결과가 새어 들지 않는다(v2 까지의 결함).
- 아침 점검(`MorningCheckJob`): 대상 = 직전 영업일 LIVE. 예상 갭 = β(지수별 주 심볼, 기준일 기준) × 밤사이 미국 1일 수익률, 임계 = `sigma-multiple`(1.0) × σ_1d(직전 60일). |갭| < 임계 → **HOLD**, 어제 방향과 같은 부호 → **REINFORCE**, 반대 부호(또는 NEUTRAL 예측에 큰 갭) → **CAUTION**. 전체 판정은 지수별 중 가장 심각한 것. 기준일에 미국이 휴장이면 새 정보가 없으므로 HOLD 로 기록만, 미국 데이터가 `max-us-lag-days`(4) 보다 오래되면 SKIPPED. **원 판단·픽·채점은 그대로** — 점검은 `tb_advisor_morning_check` 별도 행이고 관리자 `GET /advices/{id}` 의 `morningCheck` 로 보인다.
- `SignalCode.GLOBAL_LINK` 종목 시그널은 채우지 않는다(종목별 매핑 없음, 고β 는 강세장에 좋고 약세장에 나쁘므로 IC 부호가 국면에 따라 뒤집힘). VIX·미국 10년물은 `KisOverseasSymbolManualTest` 로 KIS 가 심볼을 주는지 실측한 뒤 yml `kis.overseas.symbols` 에만 추가하면 된다.

## 2. 설정 (application.yml)

| 키 | 기본 | 뜻 |
|---|---|---|
| `advisor.enabled` | `${ADVISOR_ENABLED:false}` | false 면 ChatClient·잡·컨트롤러 전부 미등록 |
| `spring.ai.openai.api-key` | `${OPENAI_API_KEY:}` | 키. `spring.ai.model.chat=none` 으로 자동구성은 꺼져 있고 `AdvisorAiConfig` 가 직접 조립 |
| `advisor.model.judge` / `assist` | `${ADVISOR_JUDGE_MODEL:}` / `${ADVISOR_ASSIST_MODEL:}` | 판단용 / 보조용 모델 ID. **코드에 박지 않는다**. 추론 모델이면 temperature 미설정 |
| `advisor.model.judge-max-completion-tokens` | 8000 | 추론 토큰 포함 출력 상한 (비용 손잡이) |
| `advisor.cost.*` | 0 | 100만 토큰당 USD. 채우면 run.cost_usd 계산 |
| `advisor.prompt.version` | `advice-v5` | 프롬프트 버전(표기용 — 실제 로드는 `PromptResources.ADVICE_VERSION`·파일명 `prompts/advisor/advice-system-v5.md`). 파일을 고치면 둘을 같이 올린다 |
| `advisor.markets` | `[KOSPI]` | 스크리닝·rank-IC 유니버스 시장(`MarketType` 코드). 빈 목록·오타는 기동 실패. **바꾸면 `IC_BACKFILL`(baseDate 없이) 재실행**으로 IC 재기준화(§1.4) |
| `advisor.trend.bull-threshold` / `bear-threshold` | 2 / −2 | 추세 성분 합 임계. 배포 후 10년 라벨 분포(§3 SQL)로 조정 — 보합 <15% 면 ±3, >55% 면 ret60 컷 0.03 |
| `advisor.trend.ret60-threshold` / `breadth-high` / `breadth-low` | 0.05 / 0.60 / 0.40 | 60일 수익률·MA20 상회 비율 성분 컷 |
| `advisor.trend.confirm-days` | 2 | 전환 확인 연속 거래일 |
| `advisor.trend.score-horizon-days` | 20 | 추세 전망 채점 창. **`diagnostic-horizons` 에 없으면 기동 시 WARN 이고 채점이 영원히 안 돈다** |
| `advisor.trend.invalidation-tolerance-days` | 2 | TREND_INV 적중: 무효화 발동일과 전환일의 허용 거리 |
| `advisor.morning.link-pairs` | `0001:SPX, 0001:SOX, 1001:COMP, 1001:SOX` | β·상관 쌍. 지수별 첫 쌍이 아침 점검 예상 갭의 주 심볼 |
| `advisor.morning.link-window-days` / `sigma-multiple` / `max-us-lag-days` | 60 / 1.0 / 4 | β 창(국내 거래일) / 아침 판정 임계 배수(× σ_1d) / 미국 데이터 허용 지연(캘린더일, 초과면 SKIPPED) |
| `scheduler.advisor-morning-check.enabled` | default false / prod true | 07:30 MON-FRI, 기동 시 평가 |
| `advisor.news.enabled` | false | 뉴스 입력 on/off. 켜면 news 블록·citedNews·LLM_NONEWS 섀도가 함께 켜진다. 선행: `scheduler.stock-news` 로 `tb_stock_news` 가 쌓여 있어야 함 |
| `advisor.news.window-hours` / `market-limit` / `per-ticker-limit` / `total-limit` / `max-chars` / `title-chars` / `cutoff` | 36 / 12 / 3 / 40 / 7000 / 120 / 20:00 | 창·상한·news 블록 자 상한·판단 마감(사후 재실행 룩어헤드 상한) |
| `advisor.shadow.nonews-weeks` | 8 | 뉴스 없는 섀도 병행 기간 |
| `kis.news.*` | path·tr-id `FHKST01011800`·provider/market/sort 공백·max-pages 5·lookback-hours 48 | 수집 API 파라미터. 필터 공백 = 전체(공식 예제와 동일), 값을 채우면 오래된 구간이 온다. 소급 창을 임시로 늘릴 땐 env `KIS_NEWS_LOOKBACKHOURS`(컨테이너 재생성 필요) |
| `scheduler.stock-news.enabled` | false (default·prod 모두) | 실측 뒤 prod true 로 |
| `advisor.horizon-days` | 5 | 결정 호라이즌. 채점·KPI·학습 전부 이 값 |
| `advisor.candidate-limit` / `max-per-sector` / `pick-min` / `pick-max` | 30 / 4 / 3 / 10 | 깔때기 |
| `advisor.advise.deadline` | 19:55 | 이후에도 DAILY 미완료면 SKIPPED + #hvy-error |
| `advisor.lesson.min-picks` | 300 | 실적 블록·보정 표·교훈 게이트(누적 LIVE 픽) |
| `advisor.ic.min-n-eff` | 24 | 가중치 세트 갱신 게이트(≈120 영업일). 사전 추정으로 충족 |
| `advisor.ic.incremental-max-days` | 45 | 증분(ADVISE·WEEKLY_REVIEW·SCORE)이 감당할 최대 공백(캘린더일). 초과분은 계산하지 않고 warnings 에 `IC 공백 …` + 메타 `icGapFrom` 을 남긴다 → `POST /jobs/IC_BACKFILL?baseDate=<icGapFrom>` 로 보충. IC 행이 없는 첫 ADVISE 가 2020 년부터 6년치를 SQL 한 번에 돌던 2026-09-13 결함 방지 |
| `advisor.shadow.reproducibility-runs` | 3 | 주간 재현성 재실행 횟수(0 이면 끔) |
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
| 뉴스 | `KisNewsTitleManualTest` 실측 → `scheduler.stock-news` on → 며칠 쌓인 뒤 `advisor.news.enabled` on | news 블록·citedNews·LLM_NONEWS 섀도(8주). **뉴스 가치는 8주 뒤 LIVE − LLM_NONEWS 로만 판정**, se 안이면 다시 off |

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
- 국면: close-to-close, 밴드 = 0.5×σ_1d×√h(직전 60일; h=5 면 σ_5d — 2026-09-13 이전엔 √5 고정이라 진단 h=1·20 밴드가 틀렸다), 밴드 안 NEUTRAL, Brier 는 부호 기준. 섹터: 업종 지수 있으면 종가, 없으면 MV 동일가중, 시장 대비 초과 >0.
- **아침 점검(advice-v3, h=1 패스만, subject_type `MORNING`)**: predicted=지수별 판정(REINFORCE/HOLD/CAUTION), actual=D+1 시가 갭 `open(D+1)/close(D)−1`, band=임계, actual_dir=|갭|<임계 NEUTRAL 아니면 부호. hit: HOLD 는 |갭|<임계, REINFORCE·CAUTION 은 예상 갭과 부호 일치. 예상 갭이 없던 지수(미국 휴장·β 결손)는 MISSING. 픽 채점은 D+1 시가 진입이라 야간 갭이 픽에는 빠지고 INDEX 콜(close→close)에는 들어간다는 비대칭을 이 행이 설명해 준다(INDEX 를 open(D+1)→close 로 바꾸는 규약 변경은 6개월 뒤 갭 기여도를 본 뒤).
- **추세 전망(advice-v2, h=20 패스만, `tb_advisor_call_score` subject_type)**: `TREND` predicted=persist 버킷, actual_dir=실현 버킷(확정 라벨이 판단 때 동결한 라벨과 처음 달라진 거래일 오프셋 1~5 → WITHIN_5D, 6~20 → ABOUT_20D, 없음 → BEYOND_20D), hit=일치, **Brier=(confidence−1[hit])² 적중 기준(INDEX 의 부호 기준과 섞지 않는다)**, event_date=전환일. `TREND_INV` predicted=무효화 타입, actual_dir=FIRED|QUIET, hit=전환·발동이 둘 다 없거나 둘 다 있고 ±tolerance 안(조기 신호로 작동), NONE 은 MISSING. 정답은 `TrendSql` 로 결정론이라 LLM 선택과 무관하다. **첫 행은 20영업일 뒤, 4주간 적중률을 판정하지 않는다.** TREND 는 픽 진입·청산과 무관한 서술 검증용이며 교훈 evidence·보정 표·가중치에 쓰지 않는다.
- KPI: 변형별(LIVE·QUANT_TOPN·LLM_NOMEM) LONG 픽 승률·평균 초과±se·후보군 평균·**부가가치**, AVOID 별도, 국면 적중·Brier skill, 보정 표, 주간 보고에 "20일 추세 지속 적중 · 무효화 신호 적중" 별도 줄. `data_quality=OK` 만.

## 8. 피드백 규율

- 가중치: `m̂ = 1 + n_eff/(n_eff+24)·(ĪC/0.03 − 1)`, clip[0.5, 2.0], Σ 재정규화, 음의 IC 는 뒤집지 않고 하한+flagged. 세트는 새 행(이력), 픽은 세트 id 참조. 수동 롤백 `POST /weights/sets/{id}/activate`.
- 교훈: 기계 판정 `condition`({regime, trend, signal, op, pct, sector}) + evidence(n≥20, |t|≥2) 필수, 종목코드 금지, 활성 ≤8, 활성 4주/적용 20건 후 (적용 − 비적용) ≤0 이면 폐기, 프롬프트에서는 **확신 조정만**. 생성기엔 누적 셀 집계표·보정 표·활성 교훈 사후 성과만 준다. `trend` 키(lesson-v2)는 스키마·판정에 있지만 생성기의 셀 집계는 아직 regime×시그널×섹터라 trend 조건 교훈은 수동 등록으로만 생긴다(후속).
- 재현성: 주 1회 직전 LIVE 입력 동결 재실행, Jaccard <0.7 이면 경고(LLM 랭킹 관여 축소 검토).
- 룩어헤드 불변식: 특징 SQL 은 기준일 이하만(`FeatureSqlTest.noLookahead`, `AdvisorScreeningPgTest.futureRowsDoNotChangeScreening`), LEAD 는 IC·채점에만, 밸류에이션은 당일 스냅샷만(과거 IC 제외), 12:00 정보 소급 금지.

## 9. 확장 훅

- 수집 항목 추가 → `FeatureSql.featureCtes()` feat CTE 컬럼 1줄 + `SignalCode` 상수 1줄 + `advisor-seed.sql` 행 1개(+ `schema-postgres.sql` 미러). 스크리닝·IC·프롬프트가 자동 반영.
- 추세 성분 추가 → `TrendSql.labelCtes()` 의 comp CTE 에 CASE 1줄 + score 합에 항 추가 + `MarketTrendService` components 맵 + `AdvisorProperties.Trend` 손잡이. 판단·채점·기저율이 자동으로 같은 정의를 쓴다.
- 연동 쌍 추가 → yml `advisor.morning.link-pairs` 에 `지수:심볼` 1개(심볼은 `kis.overseas.symbols` 에 수집돼 있어야 함). 코드 변경 없음.
- 유니버스 시장 변경(KOSDAQ 포함 등) → yml `advisor.markets` + `IC_BACKFILL` 재실행. 코드 변경 없음(§1.4).
- 뉴스 소스 추가 → `tb_stock_news.source` 값을 달리해 넣는 수집 잡 1개(`NewsItem` 생성·`StockNewsWriter.upsert`). advisor 는 source 를 가리지 않는다. DART 공시가 1순위 후보.
- 2차: 실시간 웹소켓(장중 점검 주기 확대), 백테스트(밸류 이력·상폐 유니버스 스냅샷이 쌓인 뒤). 2026-09-13 프롬프트 v2~v4 계획 원문 `~/.claude/plans/moto-planner-agent-transient-lovelace.md`.

## 10. 미실측·잔여

- `KisIndexPriceManualTest`: 지수 현재가 TR ID 실측(틀리면 rt_cd≠0, 장중 점검 INDEX 실패로 기록).
- `AdvisorOpenAiManualTest`: strict 스키마(nullable enum 포함, **v2 의 2단계 중첩 trendOutlook**) 수용 여부·토큰·지연. 프롬프트 v2 로 `promptChars` 가 v1 보다 약 1,500자 늘어난다(시장 trend 블록 2행 + dataAsOf + window).
- 추세 임계 실측: §3 의 분포 SQL 을 psql 로 돌려 yml `advisor.trend.*` 를 조정한다. breadth 성분은 MV 가 생긴 뒤에야 과거 분포를 볼 수 있다.
- `KisNewsTitleManualTest`: 경로·TR ID·응답 필드는 2026-09-13 운영 실측으로 확정(rt_cd=0, 40행 파싱). 남은 실측은 **공백 필터로 최신 기사가 오는지·`tr_cont=M` 연속조회 여부·페이지당 건수·하루 건수·종목 태그 비율**(변형 BLANK/LEGACY/BLANK_TIME 비교). 페이지당 건수 × `max-pages` 가 밤사이 건수보다 적으면 `scheduler.stock-news` cron 을 24시간(`0 5/30 * * * MON-FRI`)으로 넓힌다 — 순회는 "현재부터 과거로" 라 놓친 기사는 나중에 되찾지 못한다.
- `KisOverseasSymbolManualTest`(미작성): VIX·미국 10년물 심볼을 KIS 가 주는지. 주면 yml `kis.overseas.symbols` 추가만.
- psql 적용 순서(v2~v4 한 번에): `db/stock-schema.sql`(tb_stock_news) → `cat db/stock-derived-rebuild.sql db/stock-derived.sql | psql -1`(breadth MV) → `db/advisor-schema.sql`(13번째 테이블·ALTER 블록) → `db/advisor-seed.sql`.
- 섹터 채점의 업종 지수 코드(`sector_code` ↔ `tb_stock_index_daily.index_code`) 동일성 — 불일치면 MV 폴백이 자동 적용.
- 관리자 화면(`/admin/advisor`) 없음. 프론트는 범위 밖.
