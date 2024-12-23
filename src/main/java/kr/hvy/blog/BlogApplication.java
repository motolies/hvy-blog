package kr.hvy.blog;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = {"kr.hvy.blog", "kr.hvy.common"})
@MapperScan(basePackages = {"kr.hvy.blog.modules.*.framework.out.persistence.mapper", "kr.hvy.common.mybatis"})
@EnableAsync
public class BlogApplication {

  public static void main(String[] args) {
    SpringApplication.run(BlogApplication.class, args);
  }

}
