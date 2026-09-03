package kr.hvy.blog.modules.stock.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import kr.hvy.blog.modules.stock.client.masterfile.MasterRecord;
import kr.hvy.blog.modules.stock.domain.code.MarketType;
import kr.hvy.blog.modules.stock.domain.code.converter.MarketTypeConverter;
import kr.hvy.common.application.domain.embeddable.EventLogEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

/**
 * 종목 마스터 현재 상태 (tb_stock_master). 매일 마스터 파일로 덮어쓰며, 파일에서 사라진 종목은 삭제하지 않고
 * is_active=false + delisting_date 로 보존한다(생존편향 완화).
 */
@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StockMaster {

  private static final int NAME_LIMIT = 100;

  @Id
  @Column(length = 10)
  private String ticker;

  @Column(nullable = false, length = 100)
  private String stockName;

  @Convert(converter = MarketTypeConverter.class)
  @Column(nullable = false, length = 10)
  private MarketType marketType;

  @Column(nullable = false, length = 4)
  private String securityGroup;

  @Column(length = 12)
  private String standardCode;

  private LocalDate listingDate;

  private Long listedShares;

  private Long capital;

  @Column(precision = 18, scale = 2)
  private BigDecimal parValue;

  @Column(length = 2)
  private String settleMonth;

  @Column(length = 10)
  private String sectorLargeCode;

  @Column(length = 10)
  private String sectorMidCode;

  @Column(length = 10)
  private String sectorSmallCode;

  @Column(length = 10)
  private String kospi200Sector;

  @Column(name = "isKospi200", nullable = false)
  @Builder.Default
  private boolean kospi200 = false;

  @Column(name = "isKrx300", nullable = false)
  @Builder.Default
  private boolean krx300 = false;

  @Column(name = "isSuspended", nullable = false)
  @Builder.Default
  private boolean suspended = false;

  @Column(name = "isAdministrative", nullable = false)
  @Builder.Default
  private boolean administrative = false;

  @Column(name = "isLiquidating", nullable = false)
  @Builder.Default
  private boolean liquidating = false;

  @Column(name = "isActive", nullable = false)
  @Builder.Default
  private boolean active = true;

  private LocalDate delistingDate;

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "createdAt", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "createdBy"))
  })
  @Builder.Default
  private EventLogEntity created = EventLogEntity.defaultValues();

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "at", column = @Column(name = "updatedAt", nullable = false)),
      @AttributeOverride(name = "by", column = @Column(name = "updatedBy"))
  })
  @Builder.Default
  private EventLogEntity updated = EventLogEntity.defaultValues();

  /**
   * 마스터 파일 레코드로 새 엔티티를 만든다.
   */
  public static StockMaster fromRecord(MasterRecord record) {
    StockMaster master = StockMaster.builder().ticker(record.ticker()).build();
    master.applyFrom(record);
    return master;
  }

  /*****************************************************************************
   * 비즈니스 로직
   *****************************************************************************/

  /**
   * 마스터 파일 값으로 갱신한다. 비활성이었으면 재상장으로 보고 활성화한다.
   *
   * @return 값이 하나라도 바뀌었으면 true
   */
  public boolean applyFrom(MasterRecord record) {
    boolean changed = false;
    changed |= set(stockName, StringUtils.abbreviate(record.name(), NAME_LIMIT), v -> stockName = v);
    changed |= set(marketType, record.marketType(), v -> marketType = v);
    changed |= set(securityGroup, StringUtils.defaultIfBlank(record.groupCode(), "??"), v -> securityGroup = v);
    changed |= set(standardCode, StringUtils.defaultIfBlank(record.standardCode(), null), v -> standardCode = v);
    changed |= set(listingDate, record.listingDate(), v -> listingDate = v);
    changed |= set(listedShares, record.listedShares(), v -> listedShares = v);
    changed |= set(capital, record.capital(), v -> capital = v);
    changed |= set(parValue, record.parValue(), v -> parValue = v);
    changed |= set(settleMonth, record.settleMonth(), v -> settleMonth = v);
    changed |= set(sectorLargeCode, record.sectorLarge(), v -> sectorLargeCode = v);
    changed |= set(sectorMidCode, record.sectorMid(), v -> sectorMidCode = v);
    changed |= set(sectorSmallCode, record.sectorSmall(), v -> sectorSmallCode = v);
    changed |= set(kospi200Sector, record.kospi200Sector(), v -> kospi200Sector = v);
    changed |= set(kospi200, record.isKospi200(), v -> kospi200 = v);
    changed |= set(krx300, record.isKrx300(), v -> krx300 = v);
    changed |= set(suspended, record.isSuspended(), v -> suspended = v);
    changed |= set(administrative, record.isAdministrative(), v -> administrative = v);
    changed |= set(liquidating, record.isLiquidating(), v -> liquidating = v);
    if (!active) {
      active = true;
      delistingDate = null;
      changed = true;
    }
    if (changed) {
      updated.updated();
    }
    return changed;
  }

  /**
   * 마스터 파일에서 사라진 종목을 상장폐지(비활성)로 표시한다. 행은 지우지 않는다.
   *
   * @return 활성 → 비활성 전환이 일어났으면 true
   */
  public boolean deactivate(LocalDate delistingDate) {
    if (!active) {
      return false;
    }
    this.active = false;
    this.delistingDate = delistingDate;
    this.updated.updated();
    return true;
  }

  /**
   * 종목기본조회 결과로 보강한다: 상장일이 비어 있으면 채우고, 상장폐지일이 있으면 비활성으로 돌린다.
   *
   * @return 값이 바뀌었으면 true
   */
  public boolean enrich(LocalDate listing, LocalDate delisting) {
    boolean changed = false;
    if (listingDate == null && listing != null) {
      listingDate = listing;
      changed = true;
    }
    if (delisting != null && (active || delistingDate == null)) {
      active = false;
      delistingDate = delisting;
      changed = true;
    }
    if (changed) {
      updated.updated();
    }
    return changed;
  }

  /**
   * 유니버스 후보인지: 주권(ST)이고 활성이며 거래정지·정리매매가 아니다.
   */
  public boolean isTradableStock() {
    return active && MasterRecord.GROUP_STOCK.equals(securityGroup) && !suspended && !liquidating;
  }

  private static <T> boolean set(T current, T next, java.util.function.Consumer<T> setter) {
    if (Objects.equals(current, next)) {
      return false;
    }
    setter.accept(next);
    return true;
  }
}
