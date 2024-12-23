package kr.hvy.blog.modules.post.domain.core;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Page<T> {

  private int page;
  private int pageSize;
  private int totalCount;
  private int totalPage;
  private int begin;
  private int end;

  private List<T> list;

  public void setTotalCount(int totalCount) {
    this.totalCount = totalCount;
    this.totalPage = (int) Math.ceil((double) totalCount / (double) this.pageSize);
    this.begin = Math.max(1, this.page - 4);
    this.end = Math.min(this.page + 5, this.totalPage == 0 ? 1 : this.totalPage);
  }
}
