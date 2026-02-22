package kr.hvy.blog.infra.config;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

//@Component
public class HvyPhysicalNamingStrategy implements PhysicalNamingStrategy {

  private static final String TABLE_PREFIX = "tb_";

  @Override
  public Identifier toPhysicalCatalogName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    return name;
  }

  @Override
  public Identifier toPhysicalSchemaName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    return name;
  }

  @Override
  public Identifier toPhysicalTableName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    // 테이블에 접두사 추가하고, 소문자 스네이크 케이스로 변환
    return new Identifier(TABLE_PREFIX + convertToSnakeCase(name.getText()), name.isQuoted());
  }

  @Override
  public Identifier toPhysicalSequenceName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    return name;
  }

  @Override
  public Identifier toPhysicalColumnName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    return new Identifier(convertToSnakeCase(name.getText()), name.isQuoted());
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

}
