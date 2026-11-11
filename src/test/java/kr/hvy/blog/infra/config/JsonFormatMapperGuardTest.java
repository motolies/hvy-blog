package kr.hvy.blog.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import kr.hvy.blog.common.AbstractTestContainers;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.type.format.jackson.Jackson3JsonFormatMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * Jackson 2 공존 가드(2026-11-11). Anthropic SDK(anthropic-java-core)가 Jackson 2 databind 를 클래스패스에 올려도 이 앱의 JSON 은 Jackson 3 이어야 한다.
 * <ul>
 *   <li>Hibernate 는 JSON 컬럼 매퍼를 고를 때 Jackson 2 를 Jackson 3 보다 먼저 본다 — application.yml 의
 *       {@code hibernate.type.json_format_mapper: jackson3} 고정이 빠지면 MasterCode·AdvisorRun·StockCollectRun 의 직렬화기가 조용히 바뀐다</li>
 *   <li>Spring MVC 는 Jackson 3 을 우선하지만, 업그레이드로 감지 순서가 바뀌면 응답 JSON 형식이 달라지므로 함께 고정한다</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Jackson 2 가 클래스패스에 있어도 JSON 은 Jackson 3 으로 처리한다")
class JsonFormatMapperGuardTest extends AbstractTestContainers {

  @Autowired
  EntityManagerFactory entityManagerFactory;

  @Autowired
  RequestMappingHandlerAdapter handlerAdapter;

  @Test
  @DisplayName("전제: Jackson 2 databind 가 클래스패스에 있다(없으면 이 가드는 아무것도 지키지 않는다)")
  void jackson2_는_클래스패스에_있다() throws ClassNotFoundException {
    assertThat(Class.forName("com.fasterxml.jackson.databind.ObjectMapper")).isNotNull();
  }

  @Test
  @DisplayName("Hibernate JSON 컬럼 매퍼는 Jackson 3 이다")
  void hibernate_json_매퍼() {
    SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);

    assertThat(sessionFactory.getSessionFactoryOptions().getJsonFormatMapper()).isInstanceOf(Jackson3JsonFormatMapper.class);
  }

  @Test
  @DisplayName("MVC 메시지 컨버터에 Jackson 2 컨버터가 없다")
  void mvc_컨버터() {
    assertThat(handlerAdapter.getMessageConverters())
        .extracting(converter -> converter.getClass().getName())
        .isNotEmpty()
        .noneMatch(name -> name.contains("Jackson2"));
  }
}
