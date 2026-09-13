# AI 시장 판단(advisor) 운영 문서

- 모듈 `kr.hvy.blog.modules.advisor`, 테이블 `tb_advisor_*` 12개(`db/advisor-schema.sql`, 시드 `db/advisor-seed.sql`), REST `/api/advisor/admin/**`(ROLE_ADMIN)
- 작성 2026-09-13. 계획 원문 `~/.claude/plans/elegant-singing-glade.md`. 수집 계층은 `claudedocs/stock-collect.md`.
- **투자 자문이 아니다.** 개인 실험이며 모든 Slack 메시지에 면책 문구가 고정된다.

## 1. 한 바퀴 (선순환)

| 시각(KST) | 잡 | 내용 |
|---|---|---|
| 18:30 평일 | stock DAILY | 일봉·지표 수집 (advisor 의 입력) |
| 19:30~19:55 5분 간격 | **ADVISE** | 게이트(DAILY 완료·PRICE/DERIVED OK) → 채점·IC 증분 → 시장 특징(지수·수급·해외·섹터·σ + **규칙 추세·관측 기준일·적용 구간**) → 정량 스크리닝(유니버스 ≈1,200 → 컷 → 후보 30, 섹터당 ≤4) → 정량 top-N 섀도 → LLM 판단(strict JSON, 후보 enum) → 가드 → 저장·입력 스냅샷 → **#hvy-advisor 발행** → (메모리 활성 시) 메모리 없는 LLM 섀도 |
| 12:00 평일 | **INTRADAY** | 직전 영업일 판단을 KIS 현재가로 대조, 일치율·판정 짧은 보고(규칙 기반, 학습 미반영) |
| 08:00 일요일 | **WEEKLY_REVIEW** | 확정 재채점 → IC 가중치 세트(n_eff 게이트) → 교훈(누적 픽 게이트) → 동결 입력 재실행 Jaccard → 주간 보고 → 스냅샷 보존 정리 |
| 수동 | SCORE / IC_BACKFILL | 채점 보충 / IC 사전 추정(1회) |

학습 신호는 두 층으로 분리된다(2026-09-13 설계 검토): **시그널 가중치 = 전 유니버스 rank-IC**(하루 수백 종목), **LLM 부가가치 = 픽 − 후보군 평균 초과수익**. 픽 적중률로 가중치를 만지지 않는다.

### 1.1 시간축 (advice-v2, 2026-09-13)

판단에는 세 개의 시간축이 있다. 프롬프트·Slack·DB 가 모두 같은 값을 쓴다.

| 축 | 원천 | 누가 정하나 | 채점 |
|---|---|---|---|
| **관측 기준일** `dataAsOf` | 국내 종가 = 기준일, 수급(당일 잠정), 섹터 MV, 미국 지수 = **T-1 마감**(FX 제외 지수 심볼의 최소 날짜, `globalAgeTradingDays` 2 이상이면 Slack ⚠️) | 데이터 | — |
| **적용 구간** `window` | 진입 D+1 시가 ~ 청산 D+h 종가. `TradingCalendar`(휴장일 테이블, 미수집 날짜는 평일=개장) 로 **예정** 계산, 실제는 채점 시 `vw_stock_market_calendar` 로 재확정 | 캘린더 | 픽·INDEX 채점 창 |
| **중기 추세** `market.trend` | 지수별(0001·1001) 규칙 라벨 BULL/SIDEWAYS/BEAR — 성분 5개(종가/MA20, MA20/MA60, MA60/MA120, 60일 수익률 ±5%, MA20 상회 종목 비율 ≥0.60/≤0.40) 각 −1/0/+1 의 합이 ≥+2 강세, ≤−2 약세. **confirm-days(2) 연속 같은 raw 라벨일 때만 전환**(휩소 방지). 정의는 `TrendSql` 하나이고 판단·채점·기저율이 공유 | 규칙(LLM 이 바꿀 수 없음) | 라벨 자체는 정답이므로 채점 대상 아님 |
| **추세 지속 전망** `trendOutlook` | 지수별 `persist`(WITHIN_5D / ABOUT_20D / BEYOND_20D) + `confidence` + `invalidation`(NONE / BELOW_MA20 / BELOW_MA60 / ABOVE_MA20 / ABOVE_MA60 — 수치 레벨은 받지 않는다) | LLM | h=20 진단 패스에서 `TREND`·`TREND_INV` (§7) |

`MarketRegimeCode`(RISK_ON/NEUTRAL/RISK_OFF, 5거래일 위험 선호)와 `MarketTrendCode`(중기 추세)는 **다른 축**이다 — 강세장 안의 단기 위험 회피가 실재하므로 합치지 않는다. 시장 breadth 는 새 MV `mv_stock_market_breadth_daily`(stock 모듈, DAILY DERIVED 가 갱신)에서 온다. 교훈 condition 에 `trend` 키가 추가됐고(lesson-v2) 이 조건은 규칙이 기준일에 확정한 **오늘** 값으로 판정한다(`regime` 조건은 여전히 어제 LIVE 국면).

## 2. 설정 (application.yml)

| 키 | 기본 | 뜻 |
|---|---|---|
| `advisor.enabled` | `${ADVISOR_ENABLED:false}` | false 면 ChatClient·잡·컨트롤러 전부 미등록 |
| `spring.ai.openai.api-key` | `${OPENAI_API_KEY:}` | 키. `spring.ai.model.chat=none` 으로 자동구성은 꺼져 있고 `AdvisorAiConfig` 가 직접 조립 |
| `advisor.model.judge` / `assist` | `${ADVISOR_JUDGE_MODEL:}` / `${ADVISOR_ASSIST_MODEL:}` | 판단용 / 보조용 모델 ID. **코드에 박지 않는다**. 추론 모델이면 temperature 미설정 |
| `advisor.model.judge-max-completion-tokens` | 8000 | 추론 토큰 포함 출력 상한 (비용 손잡이) |
| `advisor.cost.*` | 0 | 100만 토큰당 USD. 채우면 run.cost_usd 계산 |
| `advisor.prompt.version` | `advice-v2` | 프롬프트 버전. 파일(`prompts/advisor/advice-system-v2.md`)을 고치면 `PromptResources.ADVICE_VERSION` 과 같이 올린다 |
| `advisor.trend.bull-threshold` / `bear-threshold` | 2 / −2 | 추세 성분 합 임계. 배포 후 10년 라벨 분포(§3 SQL)로 조정 — 보합 <15% 면 ±3, >55% 면 ret60 컷 0.03 |
| `advisor.trend.ret60-threshold` / `breadth-high` / `breadth-low` | 0.05 / 0.60 / 0.40 | 60일 수익률·MA20 상회 비율 성분 컷 |
| `advisor.trend.confirm-days` | 2 | 전환 확인 연속 거래일 |
| `advisor.trend.score-horizon-days` | 20 | 추세 전망 채점 창. **`diagnostic-horizons` 에 없으면 기동 시 WARN 이고 채점이 영원히 안 돈다** |
| `advisor.trend.invalidation-tolerance-days` | 2 | TREND_INV 적중: 무효화 발동일과 전환일의 허용 거리 |
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
3. psql: `db/advisor-schema.sql` → `db/advisor-seed.sql` (재실행 안전). **advice-v2 를 기존 설치에 올릴 때**는 같은 파일 하단의 마이그레이션 블록(`ALTER TABLE tb_advisor_advice ADD COLUMN IF NOT EXISTS trend_kospi …`, `tb_advisor_call_score.predicted/actual_dir VARCHAR(20)`, `event_date`)이 함께 실행되는지 확인한다 — `predicted` 확대를 빠뜨리면 `BEYOND_20D`(10자) 저장이 조용히 실패한다. stock 쪽은 `cat db/stock-derived-rebuild.sql db/stock-derived.sql | psql -1` 로 breadth MV 를 만든다.
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
7. `POST /api/advisor/admin/jobs/ADVISE?baseDate=<직전 영업일>` 수동 1회 → Slack 수신·`GET /advices/{id}` 확인.
8. prod `scheduler.advisor-*.enabled: true` 로 재기동.

## 4. 단계적 활성 (기간이 아니라 n 으로)

| 단계 | 진입 조건 | 켜지는 것 |
|---|---|---|
| 1차 | 배포 직후 | 스크리닝(초기 세트) + LLM + Slack + 후보 동결 + 채점 + IC 보고 + QUANT_TOPN 섀도 + 장중 점검 |
| 2차 | 누적 LIVE 픽 ≥ `lesson.min-picks`(300, ≈8주) | 실적 블록·보정 표 주입, 교훈 제안·활성, LLM_NOMEM 섀도 |
| 3차 | IC n_eff ≥ `ic.min-n-eff`(24) | 주간 가중치 세트 자동 갱신 |

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
| 일일 추천 / 장중 점검 / 주간 보고 | #hvy-advisor | 없음 |
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
- 다음 단계(계획 `~/.claude/plans/moto-planner-agent-transient-lovelace.md`): **Phase 2 미국 연동**(β·상관 입력 `market.link`, 07:30 규칙 기반 아침 점검 잡 — 19:30 판단 시점의 미국 데이터는 이미 국내 종가에 반영된 과거이므로 예측 가치는 아침에 있다), **Phase 3 뉴스**(KIS 종합 시황/공시 제목 API → `tb_stock_news`, `citedNews` id enum 가드, `LLM_NONEWS` 섀도 8주 대조 — 백테스트 불가라 섀도가 유일한 측정).
- 2차: DART, 실시간 웹소켓(장중 점검 주기 확대), 백테스트(밸류 이력·상폐 유니버스 스냅샷이 쌓인 뒤).

## 10. 미실측·잔여

- `KisIndexPriceManualTest`: 지수 현재가 TR ID 실측(틀리면 rt_cd≠0, 장중 점검 INDEX 실패로 기록).
- `AdvisorOpenAiManualTest`: strict 스키마(nullable enum 포함, **v2 의 2단계 중첩 trendOutlook**) 수용 여부·토큰·지연. 프롬프트 v2 로 `promptChars` 가 v1 보다 약 1,500자 늘어난다(시장 trend 블록 2행 + dataAsOf + window).
- 추세 임계 실측: §3 의 분포 SQL 을 psql 로 돌려 yml `advisor.trend.*` 를 조정한다. breadth 성분은 MV 가 생긴 뒤에야 과거 분포를 볼 수 있다.
- 섹터 채점의 업종 지수 코드(`sector_code` ↔ `tb_stock_index_daily.index_code`) 동일성 — 불일치면 MV 폴백이 자동 적용.
- 관리자 화면(`/admin/advisor`) 없음. 프론트는 범위 밖.
