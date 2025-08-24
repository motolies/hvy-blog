package kr.hvy.blog.infra.scheduler;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import kr.hvy.blog.modules.common.notify.domain.code.SlackChannel;
import kr.hvy.blog.modules.common.publicip.application.service.PublicIpService;
import kr.hvy.blog.modules.common.publicip.domain.RedisPublicIp;
import kr.hvy.common.infrastructure.client.rest.RestApi;
import kr.hvy.common.infrastructure.notification.slack.Notify;
import kr.hvy.common.infrastructure.notification.slack.NotifyRequest;
import kr.hvy.common.infrastructure.scheduler.impl.AbstractScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IPCheckScheduler extends AbstractScheduler {

  private final Notify notify;

  private final RestApi restApi;

  private static final String AWS_IP_CHECK_URL = "https://checkip.amazonaws.com";

  private final PublicIpService publicIpService;

  @Scheduled(cron = "${scheduler.public-ip.cron-expression}")    // 10분마다
  @SchedulerLock(name = "${scheduler.public-ip.lock-name}", lockAtLeastFor = "PT30S", lockAtMostFor = "PT50S")
  public void monitoring() {
    proceedScheduler("PUBLIC-IP-CHANGE")
        .accept(this::checkPublicIp);
  }

  private void checkPublicIp() {
    try {

      // 현재 퍼블릭 IP를 AWS를 통해 확인
      String newPublicIP = getPublicIPFromAWS();

      // 현재 저장된 public ip 체크
      // 저장된 값이 없으면 저장하고 종료
      // 저장된 값이 있으면 비교하여 다르면 알림
      Optional<RedisPublicIp> oldPublicIP = publicIpService.getPublicIP();
      oldPublicIP.ifPresentOrElse(old -> {
            if (!old.getIp().equals(newPublicIP)) {
              String msg = "Public IP changed from " + old.getIp() + " to " + newPublicIP;
              log.error(msg);
              notify.sendMessage(NotifyRequest.builder()
                  .channel(SlackChannel.NOTIFY.getChannel())
                  .message(msg)
                  .isNotify(true)
                  .build());

              // update new ip
              publicIpService.save(newPublicIP);
            }
          },
          () -> {
            publicIpService.save(newPublicIP);
          });

    } catch (IOException | InterruptedException e) {
      log.error("Error checking public IP", e);
    }
  }

  private String getPublicIPFromAWS() throws IOException, InterruptedException {
    // AWS에서 퍼블릭 IP를 확인
    return Objects.requireNonNull(restApi.get(AWS_IP_CHECK_URL, null, String.class))
        .trim();
  }


}
