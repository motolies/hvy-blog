package kr.hvy.blog.modules.tag.application.port.in;

import java.util.List;
import kr.hvy.blog.modules.tag.domain.dto.TagResponse;

public interface TagPublicUseCase {

  List<TagResponse> getAllTags();

  List<TagResponse> findByNameContainingOrderByName(String name);

}
