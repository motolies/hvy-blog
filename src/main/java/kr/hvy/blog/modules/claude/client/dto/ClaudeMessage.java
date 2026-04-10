package kr.hvy.blog.modules.claude.client.dto;

import java.util.List;

public class ClaudeMessage {

  public record Request(String model, int max_tokens, List<SystemBlock> system, List<Message> messages) {

  }

  public record SystemBlock(String type, String text) {

  }

  public record Message(String role, String content) {

  }

  public record Response(String id, String type, String model, List<Content> content, Usage usage) {

  }

  public record Content(String type, String text) {

  }

  public record Usage(int input_tokens, int output_tokens) {

  }
}
