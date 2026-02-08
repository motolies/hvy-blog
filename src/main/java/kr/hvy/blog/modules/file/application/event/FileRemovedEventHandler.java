package kr.hvy.blog.modules.file.application.event;

import kr.hvy.blog.modules.file.domain.event.FileRemovedEvent;
import kr.hvy.common.core.file.FileStoreUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 파일 삭제 이벤트 핸들러
 * 도메인 이벤트를 수신하여 실제 파일 시스템에서 파일을 삭제합니다.
 */
@Slf4j
@Component
public class FileRemovedEventHandler {

  private final String rootLocation;

  public FileRemovedEventHandler(@Value("${path.upload}") String rootLocation) {
    this.rootLocation = rootLocation;
  }

  @TransactionalEventListener
  public void handleFileRemoved(FileRemovedEvent event) {
    log.debug("File removed event received: path={}", event.filePath());
    FileStoreUtils.deleteFile(rootLocation, event.filePath());
  }
}
