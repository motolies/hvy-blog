package kr.hvy.blog.infra.active;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!default")
@ComponentScan(basePackages = {"kr.hvy.blog", "kr.hvy.common"}
    , excludeFilters = {
    @ComponentScan.Filter(type = FilterType.REGEX,
        pattern = "kr\\.hvy\\.common\\.kafka\\..*" // kafka 패키지 및 하위 클래스들 배제
    )}
)
public class ProdConfig {

}
