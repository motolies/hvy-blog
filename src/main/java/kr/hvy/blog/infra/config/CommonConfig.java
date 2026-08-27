package kr.hvy.blog.infra.config;

import kr.hvy.common.config.jackson.ObjectMapperConfigurer;
import kr.hvy.common.infrastructure.database.logging.DataSourceProxySettingProperty;
import kr.hvy.common.infrastructure.database.logging.DataSourceWrapperPostProcessor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class CommonConfig {

  /**
   * HTTP/일반 직렬화용 JsonMapper 빈.
   * Boot 4 의 JacksonAutoConfiguration 은 JsonMapper 타입 빈이 있을 때만 자체 매퍼 생성을 건너뛰므로
   * ObjectMapper 가 아닌 JsonMapper 타입으로 노출해야 hvy-common 설정(NON_NULL, ISO 날짜, 들여쓰기)이 HTTP 응답에 적용된다.
   */
  @Bean
  @Primary
  public JsonMapper jsonMapper() {
    return ObjectMapperConfigurer.getObjectMapper();
  }

  @Bean
  @ConfigurationProperties(prefix = "hvy.sql.datasource-wrapper")
  public DataSourceProxySettingProperty dataSourceProxySettingProperty() {
    return new DataSourceProxySettingProperty();
  }

  @Bean
  public DataSourceWrapperPostProcessor dataSourceWrapperPostProcessor(DataSourceProxySettingProperty dataSourceProxySettingProperty) {
    return new DataSourceWrapperPostProcessor(dataSourceProxySettingProperty);
  }


}
