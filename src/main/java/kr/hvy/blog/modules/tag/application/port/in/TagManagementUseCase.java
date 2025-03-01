package kr.hvy.blog.modules.tag.application.port.in;

import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.blog.modules.tag.domain.dto.TagCreate;
import kr.hvy.blog.modules.tag.domain.dto.TagResponse;
import kr.hvy.common.domain.dto.DeleteResponse;
import kr.hvy.common.domain.usecase.CrudUseCase;

public interface TagManagementUseCase extends CrudUseCase<Tag, TagResponse, TagCreate, Void, Long, DeleteResponse<Long>> {


}
