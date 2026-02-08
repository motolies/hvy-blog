package kr.hvy.blog.modules.category.application.event;

import kr.hvy.blog.modules.category.application.service.CategoryProcedureService;
import kr.hvy.blog.modules.category.domain.event.CategoryChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 카테고리 변경 이벤트 핸들러
 * 도메인 이벤트를 수신하여 카테고리 fullName 업데이트 프로시저를 호출합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryChangedEventHandler {

  private final CategoryProcedureService categoryProcedureService;

  @TransactionalEventListener
  public void handleCategoryChanged(CategoryChangedEvent event) {
    log.debug("Category changed event received: categoryId={}", event.categoryId());
    categoryProcedureService.updateFullName();
  }
}
