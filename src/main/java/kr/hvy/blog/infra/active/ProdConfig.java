package kr.hvy.blog.infra.active;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!default")
@ComponentScan(basePackages = {"kr.hvy.blog", "kr.hvy.common"})
public class ProdConfig {

}
