package kr.hvy.blog.modules.memo.repository.mapper;

import java.util.List;
import kr.hvy.blog.modules.memo.application.dto.MemoSearchRequest;
import kr.hvy.blog.modules.memo.application.dto.MemoSearchResult;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoMapper {

  List<MemoSearchResult> searchMemos(MemoSearchRequest request);

  int countMemos(MemoSearchRequest request);
}
