package kr.hvy.blog.infra.config;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.springframework.stereotype.Component;

@Component
public class HvyPhysicalNamingStrategy implements PhysicalNamingStrategy {

  private static final String TABLE_PREFIX = "tb_";

  @Override
  public Identifier toPhysicalCatalogName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    return apply(name);
  }

  @Override
  public Identifier toPhysicalSchemaName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    return apply(name);
  }

  @Override
  public Identifier toPhysicalTableName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    // 테이블에 접두사 추가하고, 소문자 스네이크 케이스로 변환
    return new Identifier(TABLE_PREFIX + convertToSnakeCase(name.getText()), name.isQuoted());
  }

  @Override
  public Identifier toPhysicalSequenceName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    return apply(name);
  }

  @Override
  public Identifier toPhysicalColumnName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    // 칼럼명을 파스칼 케이스로 변환
    return new Identifier(convertToPascalCase(name.getText()), name.isQuoted());
  }

  private Identifier apply(Identifier name) {
    if (name == null) {
      return null;
    }
    return name;
  }

  private String convertToSnakeCase(String name) {
    StringBuilder result = new StringBuilder();
    for (char character : name.toCharArray()) {
      if (Character.isUpperCase(character)) {
        result.append("_").append(Character.toLowerCase(character));
      } else {
        result.append(character);
      }
    }
    return result.charAt(0) == '_' ? result.substring(1) : result.toString();
  }

  private String convertToPascalCase(String name) {
    // 첫 글자를 대문자로, 그 후의 글자는 원래 상태 유지
    StringBuilder result = new StringBuilder();
    boolean isFirst = true;
    for (char character : name.toCharArray()) {
      if (isFirst) {
        result.append(Character.toUpperCase(character));
        isFirst = false;
      } else {
        result.append(character);
      }
    }
    return result.toString();
  }
}
