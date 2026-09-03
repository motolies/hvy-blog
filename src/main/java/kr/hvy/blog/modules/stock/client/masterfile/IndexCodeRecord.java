package kr.hvy.blog.modules.stock.client.masterfile;

/**
 * 업종코드 마스터(idxcode.mst) 한 줄 (IDX_CODE 구조체: 시장구분 1 + 업종코드 4 + 업종명 40).
 *
 * @param marketDiv 시장구분 1자리 (예: 00002 대형주의 맨 앞 0)
 * @param indexCode 업종코드 4자리 — FHKUP03500100 의 FID_INPUT_ISCD 로 그대로 쓴다
 * @param indexName 업종명
 */
public record IndexCodeRecord(String marketDiv, String indexCode, String indexName) {
}
