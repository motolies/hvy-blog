package kr.hvy.blog;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan(basePackages = {"kr.hvy.blog.modules.*.repository.mapper"})
@EnableJpaRepositories(basePackages = {"kr.hvy.blog", "kr.hvy.common.aop.logging"})
@EntityScan(basePackages = {"kr.hvy.blog", "kr.hvy.common.aop.logging"})
//@ComponentScan(basePackages = {"kr.hvy.blog", "kr.hvy.common.infrastructure.redis", "kr.hvy.common.infrastructure.scheduler", "kr.hvy.common.infrastructure.notification"})
@EnableAsync
public class BlogApplication {

  public static void main(String[] args) {
    SpringApplication.run(BlogApplication.class, args);
  }

}
