#!/usr/bin/env bash
# KIS 시장별 투자자매매동향(일별, FHPTJ04040000) 원본 조회. DB 저장·금액 환산·자동 연속조회 없음.
# tb_stock_market_investor_daily 적재 값과 같은 원본이며, 금액(_pbmn)·수량(_qty) 단위를 눈으로 대조하는 용도다.
set -euo pipefail
set +x

usage() {
  cat <<'HELP'
사용법:
  export KIS_APP_KEY='앱 키'
  export KIS_APP_SECRET='앱 시크릿'
  export KIS_ACCESS_TOKEN='기존 액세스 토큰'  # Bearer 접두어 없이. 생략하면 토큰 발급
  MARKET=KSP BASE_DATE=20261013 ./scripts/query-market-investor-daily.sh

환경변수:
  KIS_APP_KEY       필수
  KIS_APP_SECRET    필수
  KIS_ACCESS_TOKEN 선택. 없으면 /oauth2/tokenP로 발급하며 토큰은 출력하지 않음
  MARKET           기본 KSP (코스피). KSQ 는 코스닥. 지수코드는 자동 매핑(KSP→0001, KSQ→1001)
  BASE_DATE        기본 20261013. YYYYMMDD 또는 YYYY-MM-DD
  OUTPUT_FILE      선택. 원본 JSON을 저장할 경로. 기존 파일은 덮어쓰지 않음

필요 도구: bash, curl, python3
실전 KIS API를 기준일 1회 호출한다(연속조회 없음). 기준일 이전 여러 거래일이 포함될 수 있다.
stdout은 원본 응답, stderr는 요청 정보·요청일 수급 필드 요약이다.
금액 단위는 여기서 추정하거나 환산하지 않는다. 같은 날 뉴스 마감시황(조·억 단위)과 자릿수를 대조하면 된다.
HELP
}
if [[ "${1:-}" == '-h' || "${1:-}" == '--help' ]]; then usage; exit 0; fi
if (( $# )); then usage >&2; exit 2; fi
for cmd in curl python3; do
  command -v "$cmd" >/dev/null || { printf '필요한 명령이 없습니다: %s\n' "$cmd" >&2; exit 2; }
done
: "${KIS_APP_KEY:?KIS_APP_KEY 환경변수를 지정하세요}"
: "${KIS_APP_SECRET:?KIS_APP_SECRET 환경변수를 지정하세요}"
export KIS_APP_KEY KIS_APP_SECRET
export MARKET="${MARKET:-KSP}"
export BASE_DATE="${BASE_DATE:-20261013}"
export KIS_ACCESS_TOKEN="${KIS_ACCESS_TOKEN:-}"
export OUTPUT_FILE="${OUTPUT_FILE:-}"

# 헤더 삽입 방지, 시장 코드·날짜 검증. 출력 1줄 = 정규화된 날짜, 2줄 = 종합지수 코드.
validated=$(python3 - <<'PY'
import datetime, os, re, sys
for key in ('KIS_APP_KEY', 'KIS_APP_SECRET', 'KIS_ACCESS_TOKEN'):
    if any(c in os.environ[key] for c in '\r\n'):
        sys.exit(f'{key}: 줄바꿈은 허용하지 않습니다.')
index_by_market = {'KSP': '0001', 'KSQ': '1001'}
market = os.environ['MARKET']
if market not in index_by_market:
    sys.exit('MARKET 은 KSP 또는 KSQ 여야 합니다.')
raw = os.environ['BASE_DATE']
if not re.fullmatch(r'\d{8}|\d{4}-\d{2}-\d{2}', raw):
    sys.exit('BASE_DATE는 YYYYMMDD 또는 YYYY-MM-DD 형식이어야 합니다.')
try:
    print(datetime.datetime.strptime(raw.replace('-', ''), '%Y%m%d').strftime('%Y%m%d'))
except ValueError:
    sys.exit('BASE_DATE가 유효한 날짜가 아닙니다.')
print(index_by_market[market])
PY
)
BASE_DATE=$(printf '%s\n' "$validated" | sed -n 1p)
INDEX_CODE=$(printf '%s\n' "$validated" | sed -n 2p)
export BASE_DATE INDEX_CODE
umask 077
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/hvy-market-investor.XXXXXXXX")
trap 'rm -rf "$work_dir"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
export HVY_INVESTOR_TMP="$work_dir"
base_url='https://openapi.koreainvestment.com:9443'

if [[ -z "$KIS_ACCESS_TOKEN" ]]; then
  printf '기존 토큰이 없어 KIS 액세스 토큰을 발급합니다.\n' >&2
  python3 - <<'PY'
import json, os, pathlib
path = pathlib.Path(os.environ['HVY_INVESTOR_TMP']) / 'token-request.json'
path.write_text(json.dumps({'grant_type': 'client_credentials',
    'appkey': os.environ['KIS_APP_KEY'], 'appsecret': os.environ['KIS_APP_SECRET']}))
PY
  status=$(curl -q --silent --show-error --connect-timeout 10 --max-time 30 \
    --request POST --header 'Content-Type: application/json; charset=utf-8' \
    --data-binary "@$work_dir/token-request.json" --output "$work_dir/token-response.json" \
    --write-out '%{http_code}' "$base_url/oauth2/tokenP")
  if [[ "$status" != 2?? ]]; then
    printf '토큰 발급 실패: HTTP %s (인증정보 보호를 위해 응답은 출력하지 않습니다)\n' "$status" >&2
    exit 1
  fi
  KIS_ACCESS_TOKEN=$(python3 - <<'PY'
import json, os, pathlib, sys
try:
    body = json.loads((pathlib.Path(os.environ['HVY_INVESTOR_TMP']) / 'token-response.json').read_text())
    token = body.get('access_token')
    if not isinstance(token, str) or not token or '\r' in token or '\n' in token:
        raise ValueError()
    print(token)
except (ValueError, OSError):
    sys.exit('토큰 발급 응답에 유효한 access_token이 없습니다.')
PY
)
  export KIS_ACCESS_TOKEN
fi
# 인증 헤더를 프로세스 인수에 넣지 않는다.
python3 - <<'PY'
import os, pathlib
headers = ['Content-Type: application/json; charset=utf-8',
    'authorization: Bearer ' + os.environ['KIS_ACCESS_TOKEN'],
    'appkey: ' + os.environ['KIS_APP_KEY'], 'appsecret: ' + os.environ['KIS_APP_SECRET'],
    'tr_id: FHPTJ04040000', 'custtype: P']
(pathlib.Path(os.environ['HVY_INVESTOR_TMP']) / 'headers').write_text('\n'.join(headers) + '\n')
PY
printf '시장=%s(지수 %s) 기준일=%s / 원본 조회 (KisRestMarketDataAdapter.fetchMarketInvestorDaily 와 동일 파라미터)\n' "$MARKET" "$INDEX_CODE" "$BASE_DATE" >&2
status=$(curl -q --silent --show-error --connect-timeout 10 --max-time 30 \
  --get --header "@$work_dir/headers" \
  --data-urlencode 'FID_COND_MRKT_DIV_CODE=U' \
  --data-urlencode "FID_INPUT_ISCD=$INDEX_CODE" \
  --data-urlencode "FID_INPUT_DATE_1=$BASE_DATE" \
  --data-urlencode "FID_INPUT_ISCD_1=$MARKET" \
  --data-urlencode "FID_INPUT_DATE_2=$BASE_DATE" \
  --data-urlencode "FID_INPUT_ISCD_2=$INDEX_CODE" \
  --output "$work_dir/response.json" --write-out '%{http_code}' \
  "$base_url/uapi/domestic-stock/v1/quotations/inquire-investor-daily-by-market")
cat "$work_dir/response.json"
printf '\n'
if [[ "$status" != 2?? ]]; then printf '조회 실패: HTTP %s\n' "$status" >&2; exit 1; fi
python3 - <<'PY'
import json, os, pathlib, sys
raw = (pathlib.Path(os.environ['HVY_INVESTOR_TMP']) / 'response.json').read_bytes()
try:
    body = json.loads(raw)
except ValueError:
    sys.exit('조회 응답이 JSON이 아닙니다.')
if str(body.get('rt_cd')) != '0':
    sys.exit('KIS 조회 실패: 위 원본 응답의 rt_cd, msg_cd, msg1을 확인하세요.')
if os.environ['OUTPUT_FILE']:
    try:
        with open(os.environ['OUTPUT_FILE'], 'xb') as out:
            out.write(raw)
    except OSError as exc:
        sys.exit(f'원본 저장 실패: {exc}')
    print('원본 저장: ' + os.environ['OUTPUT_FILE'], file=sys.stderr)
rows = body.get('output') or []
print(f'수신 행 수: {len(rows)}', file=sys.stderr)
matched = [r for r in rows if r.get('stck_bsop_date') == os.environ['BASE_DATE']]
if not matched:
    dates = [r.get('stck_bsop_date') for r in rows]
    print(f'요청일 행이 없습니다. 반환 날짜: {dates}', file=sys.stderr)
for row in matched:
    print('요청일 원본 수급 값 (단위 환산 없음):', file=sys.stderr)
    for key in ('stck_bsop_date', 'frgn_ntby_tr_pbmn', 'orgn_ntby_tr_pbmn', 'prsn_ntby_tr_pbmn',
                'frgn_ntby_qty', 'orgn_ntby_qty', 'prsn_ntby_qty'):
        value = json.dumps(row[key], ensure_ascii=False) if key in row else '<필드 없음>'
        print(f'  {key}: {value}', file=sys.stderr)
    print('대조: 같은 날 마감시황 기사의 외국인/기관/개인 순매수(조·억원)와 frgn/orgn/prsn_ntby_tr_pbmn 자릿수를 비교한다. '
          '값이 10^6 배 작으면 백만원 단위, 10^3 배 작은 수량은 천주 단위다.', file=sys.stderr)
PY
