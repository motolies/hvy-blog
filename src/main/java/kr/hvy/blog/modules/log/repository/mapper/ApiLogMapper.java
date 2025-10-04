package kr.hvy.blog.modules.log.repository.mapper;

import java.util.List;
import kr.hvy.blog.modules.log.application.dto.ApiLogSearchRequest;
import kr.hvy.blog.modules.log.application.dto.ApiLogSearchResponse;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApiLogMapper {

  List<ApiLogSearchResponse> findBySearchRequest(ApiLogSearchRequest request);

}
