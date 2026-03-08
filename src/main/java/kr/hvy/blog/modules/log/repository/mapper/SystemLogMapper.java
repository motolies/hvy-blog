package kr.hvy.blog.modules.log.repository.mapper;

import java.util.List;
import kr.hvy.blog.modules.log.application.dto.SystemLogSearchCriteria;
import kr.hvy.blog.modules.log.application.dto.SystemLogSearchResponse;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SystemLogMapper {

  List<SystemLogSearchResponse> findBySearchCriteria(SystemLogSearchCriteria criteria);

}
