package kr.hvy.blog.modules.stock.domain.model;

import java.time.LocalDate;

/**
 * tb_stock_sector_map 1행. source 는 KRX(지수업종 중분류) / THEME / CUSTOM.
 */
public record SectorMapRow(String ticker, String sectorCode, LocalDate validFrom, String sectorName, String source) {

  public static final String SOURCE_KRX = "KRX";
}
