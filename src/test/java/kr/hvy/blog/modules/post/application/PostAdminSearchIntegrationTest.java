package kr.hvy.blog.modules.post.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import kr.hvy.blog.modules.post.application.dto.PostAdminSearchCriteria;
import kr.hvy.blog.modules.post.application.dto.PostAdminSearchRequest;
import kr.hvy.blog.modules.post.repository.mapper.PostMapper;
import kr.hvy.common.core.time.ClientTimeZoneResolver;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 관리자 글 목록 검색의 정렬·기간 경로 회귀 테스트.
 * <p>
 * findByAdminSearchCriteria 는 tb_post 와 tb_post_draft 를 조인하는데 두 테이블 모두 updated_at 을 가진다.
 * 백엔드 기본 정렬 컬럼에 테이블 별칭이 빠지면 "column reference is ambiguous" 로 500 이 난다.
 * 모호성은 파싱 단계 오류라 빈 테이블에서도 재현되므로 픽스처 없이 200 여부만 본다.
 * <p>
 * 애노테이션 구성은 LogSearchTimezoneIntegrationTest 와 동일하게 두어 Spring 컨텍스트 캐시를 재사용한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostAdminSearchIntegrationTest {

  private static final String STATEMENT_ID = PostMapper.class.getName() + ".findByAdminSearchCriteria";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private SqlSessionFactory sqlSessionFactory;

  @MockitoBean
  private RedissonClient redissonClient;

  /** 정렬 미지정 → 서비스가 넣는 기본 정렬(p.updated_at) 경로. 별칭이 빠지면 여기서 실패한다. */
  @Test
  void defaultSortDoesNotHitAmbiguousColumn() throws Exception {
    mockMvc.perform(search("{\"page\":0,\"pageSize\":10}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").exists());
  }

  /** 프론트(useServerGrid)가 보내는 camelCase 컬럼 id 는 SELECT 별칭으로 해석되어 매핑 없이 동작해야 한다. */
  @Test
  void frontendAliasSortWorks() throws Exception {
    mockMvc.perform(search(
            "{\"page\":0,\"pageSize\":10,\"orderBy\":[{\"column\":\"updatedAt\",\"direction\":\"DESCENDING\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").exists());
  }

  /** 기준일=수정일 + 기간 — 매퍼의 choose/include 조합이 실제 DB 에서 문법적으로 통과해야 한다. */
  @Test
  void updatedAtRangeSearchWorks() throws Exception {
    mockMvc.perform(search(
            "{\"page\":0,\"pageSize\":10,\"dateField\":\"updatedAt\","
                + "\"dateFrom\":\"2026-01-01T00:00:00\",\"dateTo\":\"2026-12-31T23:59:59\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalCount").exists());
  }

  /** 기간 조건이 걸리는 컬럼 — updatedAt 만 updated_at, 그 밖(작성일·null)은 created_at. 생성된 SQL 로 확인한다. */
  @Test
  void dateRangeAppliesToSelectedColumn() {
    assertThat(boundSql(PostAdminSearchRequest.DATE_FIELD_UPDATED_AT))
        .contains("p.updated_at >=", "p.updated_at <")
        .doesNotContain("p.created_at >=");
    assertThat(boundSql(PostAdminSearchRequest.DATE_FIELD_CREATED_AT))
        .contains("p.created_at >=", "p.created_at <")
        .doesNotContain("p.updated_at >=");
    assertThat(boundSql(null)).contains("p.created_at >=");
  }

  /**
   * 서비스를 거치지 않고 매퍼 XML 이 만드는 SQL 만 본다 — 인터셉터의 ORDER BY/LIMIT 은 실행 시에만 붙는다.
   * include 조각의 줄바꿈이 컬럼과 연산자 사이에 끼므로 공백을 한 칸으로 접어 비교한다.
   */
  private String boundSql(String dateField) {
    PostAdminSearchCriteria criteria = PostAdminSearchCriteria.builder()
        .dateField(dateField)
        .dateFrom(Instant.EPOCH)
        .dateToExclusive(Instant.EPOCH)
        .build();
    return sqlSessionFactory.getConfiguration()
        .getMappedStatement(STATEMENT_ID)
        .getBoundSql(criteria)
        .getSql()
        .replaceAll("\\s+", " ");
  }

  private MockHttpServletRequestBuilder search(String body) {
    return post("/api/post/admin/search")
        .with(user("admin").authorities(() -> "ROLE_ADMIN"))
        .contentType(MediaType.APPLICATION_JSON)
        .header(ClientTimeZoneResolver.TIMEZONE_HEADER, "Asia/Seoul")
        .header(ClientTimeZoneResolver.OFFSET_HEADER, "540")
        .content(body);
  }
}
