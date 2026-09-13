package kr.hvy.blog;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@MapperScan(basePackages = {"kr.hvy.blog.modules.*.repository.mapper"})
@EnableJpaRepositories(basePackages = {"kr.hvy.blog", "kr.hvy.common.aop.logging"})
@EntityScan(basePackages = {"kr.hvy.blog", "kr.hvy.common.aop.logging"})
@EnableAsync
@EnableScheduling
public class BlogApplication {

  public static void main(String[] args) {
    log.info("Java Version : {}", System.getProperty("java.version"));
    log.info("Java Home    : {}", System.getProperty("java.home"));
    SpringApplication.run(BlogApplication.class, args);
  }

}
