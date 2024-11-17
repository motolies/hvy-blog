package kr.hvy.blog.modules.post.domain;

import java.time.LocalDateTime;
import kr.hvy.blog.modules.post.domain.dto.PostUpdate;
import kr.hvy.blog.modules.post.domain.specification.PostUpdateSpecification;
import kr.hvy.common.domain.vo.CreateUpdateDate;
import org.springframework.stereotype.Service;

@Service
public class PostService {
  private final PostUpdateSpecification postUpdateSpecification = new PostUpdateSpecification();

  public Post update(Post post, PostUpdate update) {

    postUpdateSpecification.validateException(update);

    CreateUpdateDate updatedCreateUpdateDate = post.getCreateUpdateDate()
        .withUpdateDate(LocalDateTime.now());

    return post
        .withStatus(update.getStatus())
        .withSubject(update.getSubject())
        .withBody(update.getBody())
        .withCategoryId(update.getCategoryId())
        .withPublic(update.isPublic())
        .withMain(update.isMain())
        .withCreateUpdateDate(updatedCreateUpdateDate);
  }

}
