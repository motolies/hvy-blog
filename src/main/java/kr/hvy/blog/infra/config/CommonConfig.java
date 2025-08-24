package kr.hvy.blog.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hvy.common.config.jackson.ObjectMapperConfigurer;
import kr.hvy.common.infrastructure.database.logging.DataSourceProxySettingProperty;
import kr.hvy.common.infrastructure.database.logging.DataSourceWrapperPostProcessor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class CommonConfig {

  @Bean
  @Primary
  public ObjectMapper objectMapper() {
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
