package kr.hvy.blog.modules.post.repository.mapper;

import java.util.List;
import kr.hvy.blog.modules.post.application.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.application.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.application.dto.SearchObject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PostMapper {

  List<PostNoBodyResponse> findBySearchObject(SearchObject searchObject);

  PostPrevNextResponse findPrevNextById(boolean isAdmin, Long id);

  List<Long> findByPublicPosts();

  void setMainPost(@Param("id") Long id);
}
