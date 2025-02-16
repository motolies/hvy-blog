package kr.hvy.blog.modules.post.adapter.out.persistence.mapper;

import java.util.List;
import kr.hvy.blog.modules.post.domain.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.domain.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.domain.dto.SearchObjectDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PostRDBMapper {

  List<PostNoBodyResponse> findBySearchObject(boolean isAdmin, @Param("obj") SearchObjectDto searchObjectDto);

  PostPrevNextResponse findPrevNextById(boolean isAdmin, Long id);

  List<Long> findByTempPosts();

  List<Long> findByPublicPosts();

  void setMainPost(@Param("id") Long id);
}
