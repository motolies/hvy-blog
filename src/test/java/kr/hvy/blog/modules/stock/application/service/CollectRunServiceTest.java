package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class CollectRunServiceTest {

  /**
   * 이 서비스의 public 메서드는 전부 DB 를 건드리고, 잡 스레드(트랜잭션 없는 컨텍스트)에서 직접 호출된다.
   * 애노테이션이 빠지면 {@code @Modifying} 벌크 UPDATE 가 TransactionRequiredException 으로 터지고,
   * 같은 빈 안에서 다른 메서드로 위임해도 프록시를 타지 않아 상대의 경계를 빌려올 수 없다.
   */
  @Test
  @DisplayName("public 메서드는 모두 자기 트랜잭션 경계를 선언한다 (자기 호출로 프록시를 우회하지 못하므로)")
  void everyPublicMethodDeclaresItsOwnTransaction() {
    List<String> missing = Arrays.stream(CollectRunService.class.getDeclaredMethods())
        .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
        .filter(method -> !method.isSynthetic())
        .filter(method -> !method.isAnnotationPresent(Transactional.class))
        .map(Method::getName)
        .toList();

    assertThat(missing).isEmpty();
  }
}
