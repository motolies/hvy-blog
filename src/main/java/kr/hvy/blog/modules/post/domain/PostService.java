package kr.hvy.blog.modules.post.domain;

import java.time.LocalDateTime;
import java.util.Set;
import kr.hvy.blog.modules.file.domain.File;
import kr.hvy.blog.modules.post.domain.dto.PostUpdate;
import kr.hvy.blog.modules.post.domain.specification.PostAuthoritySpecification;
import kr.hvy.blog.modules.post.domain.specification.PostUpdateSpecification;
import kr.hvy.common.domain.vo.EventLog;
import kr.hvy.common.security.SecurityUtils;
import org.springframework.stereotype.Service;

@Service
public class PostService {

  private final PostUpdateSpecification postUpdateSpecification = new PostUpdateSpecification();
  private final PostAuthoritySpecification postAuthoritySpecification = new PostAuthoritySpecification();


  public Post update(Post post, PostUpdate update) {

    postUpdateSpecification.validateException(update);

    EventLog updateLog = EventLog.builder()
        .at(LocalDateTime.now())
        .by(SecurityUtils.getUsername())
        .build();

    return post
        .withSubject(update.getSubject())
        .withBody(update.getBody())
        .withCategoryId(update.getCategoryId())
        .withPublic(update.isPublic())
        .withMain(update.isMain())
        .withUpdated(updateLog);
  }

  public void checkAuthority(Post post) {
    if (!postAuthoritySpecification.isSatisfiedBy(post)) {
      throw new RuntimeException(postAuthoritySpecification.getErrorMessage());
    }
  }

  public Post setPostVisible(Post post, boolean visible) {
    return post.withPublic(visible);
  }

}
