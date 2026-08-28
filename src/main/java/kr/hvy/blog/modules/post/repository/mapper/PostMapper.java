package kr.hvy.blog.modules.post.repository.mapper;

import java.util.List;
import kr.hvy.blog.modules.post.application.dto.PostAdminSearchCriteria;
import kr.hvy.blog.modules.post.application.dto.PostAdminSearchResponse;
import kr.hvy.blog.modules.post.application.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.application.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.application.dto.PostRelatedResponse;
import kr.hvy.blog.modules.post.application.dto.SearchObject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PostMapper {

  List<PostNoBodyResponse> findBySearchObject(SearchObject searchObject);

  /**
   * 관리자 글 목록. 공개 검색과 SQL 을 공유하지 않고 별도 쿼리를 두는 이유는
   * PostAdminSearchRequest 의 주석 참고 — 공개 검색의 결과 건수·페이징을 건드리지 않기 위함이다.
   */
  List<PostAdminSearchResponse> findByAdminSearchCriteria(PostAdminSearchCriteria criteria);

  PostPrevNextResponse findPrevNextById(boolean isAdmin, Long id);

  List<Long> findByPublicPosts();

  void setMainPost(@Param("id") Long id);

  List<PostRelatedResponse> findRelatedPosts(@Param("postId") Long postId, @Param("limit") int limit);
}
