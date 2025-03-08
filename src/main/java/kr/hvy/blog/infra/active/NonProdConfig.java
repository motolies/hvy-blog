package kr.hvy.blog.infra.active;


import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("default")
@ComponentScan(
    basePackages = {"kr.hvy.blog", "kr.hvy.common"},
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "kr\\.hvy\\.common\\.aop\\.log\\..*" // 해당 패키지 및 하위 클래스들 배제
        )
    }
)
public class NonProdConfig {
  /**
   * SystemLogAspect 계속 올라온 이유는 jpa 설정하면서 같이 딸려서 올라온 것 같음
   * 앱에서 profile로 변경하도록 하여 처리
    */
}
