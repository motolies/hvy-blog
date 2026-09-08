package kr.hvy.blog.modules.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import kr.hvy.blog.modules.stock.client.KisMarketDataPort;
import kr.hvy.blog.modules.stock.client.KisProperties;
import kr.hvy.blog.modules.stock.repository.jdbc.CorporateActionWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상폐 보강 후보: 기업행사−마스터 차집합 중 REST 가 거부하는 형식(6자 영숫자 밖)은 자동 경로에서도 제외한다.
 */
class CorporateActionCollectServiceTest {

  private final CorporateActionWriter actionWriter = mock(CorporateActionWriter.class);
  private final CorporateActionCollectService service = new CorporateActionCollectService(
      mock(KisMarketDataPort.class), actionWriter, new KisProperties());

  @Test
  @DisplayName("Q 접두 ETN·7자·5자리 같은 형식 밖 코드는 걸러지고 6자 코드(우선주 포함)만 남는다")
  void tickersMissingFromMasterFiltersMalformedCodes() {
    when(actionWriter.tickersMissingFromMaster()).thenReturn(Arrays.asList("003410", "Q500001", "A005930", "12345", "003415", null));

    assertThat(service.tickersMissingFromMaster()).containsExactly("003410", "003415");
  }
}
