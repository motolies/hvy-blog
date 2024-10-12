package kr.hvy.blog.modules.post.application.port.in;

import kr.hvy.blog.modules.post.domain.dto.PostCreate;
import kr.hvy.blog.modules.post.domain.dto.PostResponse;
import kr.hvy.blog.modules.post.domain.model.Post;
import kr.hvy.common.domain.usecase.CrudUseCase;

public interface PostManagementUseCase extends CrudUseCase<Post, PostResponse, PostCreate, Void, Long> {

}
