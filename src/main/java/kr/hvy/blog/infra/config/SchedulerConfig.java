package kr.hvy.blog.infra.config;

import javax.sql.DataSource;
import kr.hvy.common.config.SchedulerConfigurer;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SchedulerConfig extends SchedulerConfigurer {

  @Bean
  public LockProvider lockProvider(DataSource dataSource) {
    return lockProviderByJdbc(dataSource);
  }

}
