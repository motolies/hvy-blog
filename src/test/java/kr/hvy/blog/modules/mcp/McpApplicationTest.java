package kr.hvy.blog.modules.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import kr.hvy.blog.common.AbstractTestContainers;
import kr.hvy.blog.modules.advisor.client.openai.OpenAiResponsesChatModel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** Real application scanning, security chains and REST response advice. No external credentials. */
@ActiveProfiles("test")
class McpApplicationTest {
  @Nested
  @org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
      "MCP_ENABLED=true", "MCP_API_KEY=context-test-key", "advisor.enabled=false",
      "spring.ai.openai.api-key=", "slack.token=", "advisor.chat.enabled=false",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect"})
  class ReadOnly extends AbstractTestContainers {
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.postgresql.PostgreSQLContainer PG = new org.testcontainers.postgresql.PostgreSQLContainer("postgres:18-alpine");
    static { PG.start(); }
    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
      registry.add("spring.datasource.url", PG::getJdbcUrl);
      registry.add("spring.datasource.username", PG::getUsername);
      registry.add("spring.datasource.password", PG::getPassword);
      registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    @LocalServerPort int port;
    @Autowired ApplicationContext context;

    @Test void realSecurityAndJsonRpcCoexistWithoutAdvisor() throws Exception {
      assertThat(context.getBeansOfType(McpConfig.class)).hasSize(1);
      assertThat(context.getBeansOfType(OpenAiResponsesChatModel.class)).isEmpty();
      try (var connection = java.sql.DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
        for (String file : java.util.List.of("stock-schema.sql", "stock-derived.sql", "advisor-schema.sql"))
          org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ClassPathResource("db/" + file));
      }
      var http = HttpClient.newHttpClient();
      var admin = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/advisor/admin/advices"))
          .header("Authorization", "Bearer context-test-key").GET().build(), HttpResponse.BodyHandlers.ofString());
      assertThat(admin.statusCode()).isIn(401, 403);
      var transport = io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
          .endpoint("/mcp").requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer context-test-key"))
          .openConnectionOnStartup(false).build();
      try (var client = io.modelcontextprotocol.client.McpClient.sync(transport).build()) {
        assertThat(client.initialize().serverInfo().name()).isEqualTo("hvy-stock-advice");
        for (String name : java.util.List.of("dataFreshness", "listAdvices", "advicePerformance"))
          assertThat(client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(name, Map.of())).isError()).as(name).isFalse();
        assertThat(client.listTools().tools()).hasSize(McpConfig.READ_TOOLS.size());
        assertThat(client.readResource(new io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest("stock://guide")).contents()).isNotEmpty();
      }
    }
  }

  @Nested
  @org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
  @SpringBootTest(properties = {"MCP_ENABLED=true", "MCP_API_KEY=context-test-key", "advisor.enabled=true",
      "spring.ai.openai.api-key=", "slack.token=", "advisor.chat.enabled=false"})
  class WithAdvisor extends AbstractTestContainers {
    @Autowired ApplicationContext context;
    @Test void bothModulesStartWithoutCredentials() {
      assertThat(context.getBeansOfType(McpConfig.class)).hasSize(1);
      assertThat(context.getBeansOfType(OpenAiResponsesChatModel.class)).hasSize(3);
    }
  }

  @Nested
  @org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
  @SpringBootTest(properties = {"MCP_ENABLED=false", "advisor.enabled=false", "MCP_API_KEY="})
  class Disabled extends AbstractTestContainers {
    @Autowired ApplicationContext context;
    @Test void disabledDoesNotRegisterMcp() {
      assertThat(context.getBeansOfType(McpConfig.class)).isEmpty();
    }
  }
}
