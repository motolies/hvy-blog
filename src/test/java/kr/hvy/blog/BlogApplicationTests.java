package kr.hvy.blog;

import kr.hvy.blog.common.AbstractTestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")  // 'application-test.yml' 사용
class BlogApplicationTests extends AbstractTestContainers {

  @Test
  void contextLoads() {
  }

}
