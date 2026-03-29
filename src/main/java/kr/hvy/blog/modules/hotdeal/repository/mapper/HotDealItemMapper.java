package kr.hvy.blog.modules.hotdeal.repository.mapper;

import java.util.List;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealItemSearchCriteria;
import kr.hvy.blog.modules.hotdeal.application.dto.HotDealItemSearchResponse;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HotDealItemMapper {

  List<HotDealItemSearchResponse> findBySearchCriteria(HotDealItemSearchCriteria criteria);

}
