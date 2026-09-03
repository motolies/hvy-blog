package kr.hvy.blog.modules.stock.client.masterfile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import kr.hvy.blog.modules.stock.client.KisProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 종목 마스터 zip 파일 다운로드. KIS API 도메인이 아닌 정적 배포 서버라 토큰·레이트 리미터가 필요 없고,
 * ApiLogInterceptor 도 붙이지 않기 위해 JDK HttpClient 를 직접 쓴다.
 */
@Slf4j
@Component
public class MasterFileDownloader {

  private final KisProperties properties;
  private final HttpClient httpClient;

  public MasterFileDownloader(KisProperties properties) {
    this.properties = properties;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.getHttp().getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  /**
   * {baseUrl}/{fileName}.mst.zip 을 받아 압축을 풀고 .mst 본문 바이트를 돌려준다.
   */
  public byte[] download(String fileName) {
    URI uri = URI.create(properties.getMasterFile().getBaseUrl() + "/" + fileName + ".mst.zip");
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(60))
        .GET()
        .build();
    try {
      HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("마스터 파일 다운로드 실패: " + uri + " status=" + response.statusCode());
      }
      byte[] body = unzipFirstEntry(response.body(), fileName);
      log.info("마스터 파일 다운로드: {} zip={}B, mst={}B", fileName, response.body().length, body.length);
      return body;
    } catch (IOException e) {
      throw new UncheckedIOException("마스터 파일 다운로드 실패: " + uri, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("마스터 파일 다운로드 중 인터럽트: " + uri, e);
    }
  }

  /**
   * zip 안의 .mst 항목(없으면 첫 항목)을 읽는다.
   */
  static byte[] unzipFirstEntry(byte[] zip, String fileName) throws IOException {
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry entry;
      byte[] fallback = null;
      while ((entry = in.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        byte[] bytes = in.readAllBytes();
        if (entry.getName().endsWith(".mst")) {
          return bytes;
        }
        if (fallback == null) {
          fallback = bytes;
        }
      }
      if (fallback == null) {
        throw new IOException("zip 에 항목이 없습니다: " + fileName);
      }
      return fallback;
    }
  }
}
