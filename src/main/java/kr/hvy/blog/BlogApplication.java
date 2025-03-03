package kr.hvy.blog;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = {"kr.hvy.blog", "kr.hvy.common"})
@MapperScan(basePackages = {"kr.hvy.blog.modules.*.adapter.out.persistence.mapper", "kr.hvy.common.mybatis"})
@EnableJpaRepositories(basePackages = {"kr.hvy.blog", "kr.hvy.common"})
@EntityScan(basePackages = {"kr.hvy.blog", "kr.hvy.common"})
@EnableAsync
public class BlogApplication {

  public static void main(String[] args) {
    SpringApplication.run(BlogApplication.class, args);
  }

}
