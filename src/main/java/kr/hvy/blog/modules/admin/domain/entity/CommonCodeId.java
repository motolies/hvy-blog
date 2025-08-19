package kr.hvy.blog.modules.admin.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/**
 * CommonCode 복합키 클래스
 * JPA @IdClass 어노테이션과 함께 사용
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommonCodeId implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * 클래스명 (예: REGION_CLASS, SEOUL_DISTRICT_CLASS)
   */
  private String className;

  /**
   * 코드값 (예: SEOUL, BUSAN, GANGNAM)
   */
  private String code;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    CommonCodeId that = (CommonCodeId) o;
    return Objects.equals(className, that.className) && Objects.equals(code, that.code);
  }

  @Override
  public int hashCode() {
    return Objects.hash(className, code);
  }

  @Override
  public String toString() {
    return "CommonCodeId{" +
        "className='" + className + '\'' +
        ", code='" + code + '\'' +
        '}';
  }
}
