package kr.hvy.blog.modules.post.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kr.hvy.blog.modules.category.domain.entity.Category;
import kr.hvy.blog.modules.category.repository.CategoryRepository;
import kr.hvy.blog.modules.file.application.dto.FileCreate;
import kr.hvy.blog.modules.file.application.dto.FileResponse;
import kr.hvy.blog.modules.file.domain.entity.File;
import kr.hvy.blog.modules.file.mapper.FileDtoMapper;
import kr.hvy.blog.modules.post.application.dto.PostCreate;
import kr.hvy.blog.modules.post.application.dto.PostNoBodyResponse;
import kr.hvy.blog.modules.post.application.dto.PostPrevNextResponse;
import kr.hvy.blog.modules.post.application.dto.PostPublicRequest;
import kr.hvy.blog.modules.post.application.dto.PostResponse;
import kr.hvy.blog.modules.post.application.dto.PostUpdate;
import kr.hvy.blog.modules.post.application.dto.SearchObject;
import kr.hvy.blog.modules.post.domain.entity.Post;
import kr.hvy.blog.modules.post.mapper.PostDtoMapper;
import kr.hvy.blog.modules.post.repository.PostRepository;
import kr.hvy.blog.modules.post.repository.mapper.PostMapper;
import kr.hvy.blog.modules.tag.application.dto.TagCreate;
import kr.hvy.blog.modules.tag.application.dto.TagResponse;
import kr.hvy.blog.modules.tag.application.service.TagService;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.blog.modules.tag.mapper.TagDtoMapper;
import kr.hvy.common.application.domain.dto.DeleteResponse;
import kr.hvy.common.core.exception.DataNotFoundException;
import kr.hvy.common.core.exception.SpecificationException;
import kr.hvy.common.core.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

  @Mock
  EntityManager entityManager;

  @Mock
  TagService tagService;

  @Mock
  FileDtoMapper fileDtoMapper;

  @Mock
  TagDtoMapper tagDtoMapper;

  @Mock
  PostDtoMapper postDtoMapper;

  @Mock
  PostRepository postRepository;

  @Mock
  PostMapper postMapper;

  @Mock
  CategoryRepository categoryRepository;

  @InjectMocks
  PostService postService;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(postService, "entityManager", entityManager);
  }

  private Post createPost(Long id, boolean publicAccess, boolean mainPage) {
    return Post.builder()
        .id(id)
        .subject("테스트 제목")
        .body("<p>본문</p>")
        .normalBody("본문")
        .publicAccess(publicAccess)
        .mainPage(mainPage)
        .tags(new HashSet<>())
        .files(new HashSet<>())
        .build();
  }

  private Category createCategory(String id, String name) {
    return Category.builder()
        .id(id)
        .name(name)
        .seq(1)
        .fullName(name)
        .fullPath("/" + name + "/")
        .categories(List.of())
        .build();
  }

  private PostResponse createPostResponse(Long id, String subject) {
    return PostResponse.builder()
        .id(id)
        .subject(subject)
        .build();
  }

  @Nested
  @DisplayName("findByIdCheckAuthority")
  class FindByIdCheckAuthority {

    @Test
    @DisplayName("관리자이면 비공개 포스트도 반환한다")
    void findByIdCheckAuthority_adminUser_returnsPost() {
      try (MockedStatic<SecurityUtils> securityMock = mockStatic(SecurityUtils.class)) {
        // Given
        Post post = createPost(1L, false, false);
        securityMock.when(SecurityUtils::hasAdminRole).thenReturn(true);
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        // When
        Post result = postService.findByIdCheckAuthority(1L);

        // Then
        assertThat(result).isEqualTo(post);
      }
    }

    @Test
    @DisplayName("공개 포스트이면 비관리자도 조회할 수 있다")
    void findByIdCheckAuthority_publicPost_returnsPost() {
      try (MockedStatic<SecurityUtils> securityMock = mockStatic(SecurityUtils.class)) {
        // Given
        Post post = createPost(1L, true, false);
        securityMock.when(SecurityUtils::hasAdminRole).thenReturn(false);
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        // When
        Post result = postService.findByIdCheckAuthority(1L);

        // Then
        assertThat(result).isEqualTo(post);
      }
    }

    @Test
    @DisplayName("비공개 포스트에 비관리자가 접근하면 SpecificationException이 발생한다")
    void findByIdCheckAuthority_privatePostNonAdmin_throwsSpecificationException() {
      try (MockedStatic<SecurityUtils> securityMock = mockStatic(SecurityUtils.class)) {
        // Given
        Post post = createPost(1L, false, false);
        securityMock.when(SecurityUtils::hasAdminRole).thenReturn(false);
        given(postRepository.findById(1L)).willReturn(Optional.of(post));

        // When & Then
        assertThatThrownBy(() -> postService.findByIdCheckAuthority(1L))
            .isInstanceOf(SpecificationException.class);
      }
    }

    @Test
    @DisplayName("존재하지 않는 ID이면 DataNotFoundException이 발생한다")
    void findByIdCheckAuthority_nonExistingId_throwsDataNotFoundException() {
      // Given
      given(postRepository.findById(999L)).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> postService.findByIdCheckAuthority(999L))
          .isInstanceOf(DataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("findByMain")
  class FindByMain {

    @Test
    @DisplayName("메인 포스트가 존재하면 반환한다")
    void findByMain_mainPostExists_returnsPost() {
      // Given
      Post mainPost = createPost(1L, true, true);
      given(postRepository.findByMainPage(true)).willReturn(List.of(mainPost));

      // When
      Post result = postService.findByMain();

      // Then
      assertThat(result).isEqualTo(mainPost);
    }

    @Test
    @DisplayName("메인 포스트가 없으면 가장 오래된 공개 포스트를 반환한다")
    void findByMain_noMainPost_returnsOldestPublicPost() {
      // Given
      Post publicPost = createPost(1L, true, false);
      given(postRepository.findByMainPage(true)).willReturn(List.of());
      given(postRepository.findTopByPublicAccessOrderById(true)).willReturn(Optional.of(publicPost));

      // When
      Post result = postService.findByMain();

      // Then
      assertThat(result).isEqualTo(publicPost);
    }

    @Test
    @DisplayName("메인 포스트와 공개 포스트가 모두 없으면 DataNotFoundException이 발생한다")
    void findByMain_noPublicPost_throwsDataNotFoundException() {
      // Given
      given(postRepository.findByMainPage(true)).willReturn(List.of());
      given(postRepository.findTopByPublicAccessOrderById(true)).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> postService.findByMain())
          .isInstanceOf(DataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("findPrevNextById")
  class FindPrevNextById {

    @Test
    @DisplayName("정상 조회하면 이전/다음 포스트 정보를 반환한다")
    void findPrevNextById_validId_returnsPrevNextResponse() {
      // Given
      PostPrevNextResponse response = PostPrevNextResponse.builder().build();
      given(postMapper.findPrevNextById(false, 1L)).willReturn(response);

      // When
      PostPrevNextResponse result = postService.findPrevNextById(false, 1L);

      // Then
      assertThat(result).isEqualTo(response);
    }
  }

  @Nested
  @DisplayName("findByPublicPosts")
  class FindByPublicPosts {

    @Test
    @DisplayName("공개 포스트 ID 목록을 반환한다")
    void findByPublicPosts_returnsIdList() {
      // Given
      List<Long> ids = List.of(1L, 2L, 3L);
      given(postMapper.findByPublicPosts()).willReturn(ids);

      // When
      List<Long> result = postService.findByPublicPosts();

      // Then
      assertThat(result).isEqualTo(ids);
    }
  }

  @Nested
  @DisplayName("findBySearchObject")
  class FindBySearchObject {

    @Test
    @DisplayName("검색 조건으로 포스트 목록을 반환한다")
    void findBySearchObject_validSearch_returnsList() {
      // Given
      SearchObject searchObject = mock(SearchObject.class);
      List<PostNoBodyResponse> responses = List.of(mock(PostNoBodyResponse.class));
      given(postMapper.findBySearchObject(searchObject)).willReturn(responses);

      // When
      List<PostNoBodyResponse> result = postService.findBySearchObject(searchObject);

      // Then
      assertThat(result).hasSize(1);
    }
  }

  @Nested
  @DisplayName("setMainPost")
  class SetMainPost {

    @Test
    @DisplayName("메인 포스트 설정 시 postMapper.setMainPost가 호출된다")
    void setMainPost_callsPostMapperSetMainPost() {
      // Given
      Long id = 1L;

      // When
      postService.setMainPost(id);

      // Then
      then(postMapper).should().setMainPost(id);
    }
  }

  @Nested
  @DisplayName("create")
  class Create {

    @Test
    @DisplayName("정상 요청이면 PostResponse를 반환한다")
    void create_validRequest_returnsPostResponse() {
      // Given
      PostCreate createDto = PostCreate.builder()
          .subject("제목").body("본문").categoryId("cat1").isPublic(true).build();
      Post post = createPost(1L, true, false);
      Category category = createCategory("cat1", "Java");
      PostResponse response = createPostResponse(1L, "제목");

      given(postRepository.findBySubjectAndBody("", "")).willReturn(List.of());
      given(postDtoMapper.toDomain(createDto)).willReturn(post);
      given(categoryRepository.findById("cat1")).willReturn(Optional.of(category));
      given(postRepository.save(any())).willReturn(post);
      given(postDtoMapper.toResponse(post)).willReturn(response);

      // When
      PostResponse result = postService.create(createDto);

      // Then
      assertThat(result).isEqualTo(response);
    }

    @Test
    @DisplayName("카테고리가 없으면 DataNotFoundException이 발생한다")
    void create_categoryNotFound_throwsDataNotFoundException() {
      // Given
      PostCreate createDto = PostCreate.builder()
          .subject("제목").body("본문").categoryId("nonExistent").build();
      Post post = createPost(1L, false, false);

      given(postRepository.findBySubjectAndBody("", "")).willReturn(List.of());
      given(postDtoMapper.toDomain(createDto)).willReturn(post);
      given(categoryRepository.findById("nonExistent")).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> postService.create(createDto))
          .isInstanceOf(DataNotFoundException.class);
    }

    @Test
    @DisplayName("임시 포스트가 있으면 삭제 후 새 포스트를 생성한다")
    void create_tempPostsExist_deletesTempPostsBeforeCreate() {
      // Given
      PostCreate createDto = PostCreate.builder()
          .subject("제목").body("본문").categoryId("cat1").build();
      Post tempPost = createPost(99L, false, false);
      Post newPost = createPost(1L, false, false);
      Category category = createCategory("cat1", "Java");
      PostResponse response = createPostResponse(1L, "제목");

      given(postRepository.findBySubjectAndBody("", "")).willReturn(List.of(tempPost));
      given(postDtoMapper.toDomain(createDto)).willReturn(newPost);
      given(categoryRepository.findById("cat1")).willReturn(Optional.of(category));
      given(postRepository.save(any())).willReturn(newPost);
      given(postDtoMapper.toResponse(newPost)).willReturn(response);

      // When
      postService.create(createDto);

      // Then
      then(postRepository).should().deleteAll(List.of(tempPost));
      then(postRepository).should().flush();
    }
  }

  @Nested
  @DisplayName("update")
  class Update {

    @Test
    @DisplayName("정상 요청이면 업데이트된 PostResponse를 반환한다")
    void update_validRequest_returnsPostResponse() {
      try (MockedStatic<SecurityUtils> securityMock = mockStatic(SecurityUtils.class)) {
        // Given
        PostUpdate updateDto = PostUpdate.builder()
            .subject("새 제목").body("새 본문").categoryId("cat1").isPublic(true).isMain(false).build();
        Post mockPost = mock(Post.class);
        Category category = createCategory("cat1", "Java");
        PostResponse response = createPostResponse(1L, "새 제목");

        securityMock.when(SecurityUtils::getUsername).thenReturn("admin");
        given(postRepository.findById(1L)).willReturn(Optional.of(mockPost));
        given(categoryRepository.findById("cat1")).willReturn(Optional.of(category));
        given(postRepository.saveAndFlush(any())).willReturn(mockPost);
        given(postDtoMapper.toResponse(any())).willReturn(response);

        // When
        PostResponse result = postService.update(1L, updateDto);

        // Then
        assertThat(result).isEqualTo(response);
      }
    }

    @Test
    @DisplayName("포스트가 없으면 DataNotFoundException이 발생한다")
    void update_postNotFound_throwsDataNotFoundException() {
      // Given
      PostUpdate updateDto = PostUpdate.builder()
          .subject("제목").body("본문").categoryId("cat1").build();
      given(postRepository.findById(999L)).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> postService.update(999L, updateDto))
          .isInstanceOf(DataNotFoundException.class);
    }

    @Test
    @DisplayName("카테고리가 없으면 DataNotFoundException이 발생한다")
    void update_categoryNotFound_throwsDataNotFoundException() {
      // Given
      PostUpdate updateDto = PostUpdate.builder()
          .subject("제목").body("본문").categoryId("nonExistent").build();
      Post mockPost = mock(Post.class);
      given(postRepository.findById(1L)).willReturn(Optional.of(mockPost));
      given(categoryRepository.findById("nonExistent")).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> postService.update(1L, updateDto))
          .isInstanceOf(DataNotFoundException.class);
    }

    @Test
    @DisplayName("제목이 빈 값이면 SpecificationException이 발생한다")
    void update_emptySubject_throwsSpecificationException() {
      // Given
      PostUpdate updateDto = PostUpdate.builder()
          .subject("").body("본문").categoryId("cat1").build();

      // When & Then
      assertThatThrownBy(() -> postService.update(1L, updateDto))
          .isInstanceOf(SpecificationException.class);
    }
  }

  @Nested
  @DisplayName("delete")
  class Delete {

    @Test
    @DisplayName("정상 요청이면 DeleteResponse를 반환하고 deleteById가 호출된다")
    void delete_validId_returnsDeleteResponse() {
      // Given
      Long id = 1L;

      // When
      DeleteResponse<Long> result = postService.delete(id);

      // Then
      assertThat(result.getId()).isEqualTo(id);
      then(postRepository).should().deleteById(id);
    }
  }

  @Nested
  @DisplayName("setPostVisible")
  class SetPostVisible {

    @Test
    @DisplayName("정상 요청이면 공개 상태가 변경된 PostResponse를 반환한다")
    void setPostVisible_validRequest_returnsPostResponse() {
      // Given
      PostPublicRequest request = PostPublicRequest.builder().id(1L).publicStatus(true).build();
      Post post = createPost(1L, false, false);
      PostResponse response = createPostResponse(1L, "제목");
      given(postRepository.findById(1L)).willReturn(Optional.of(post));
      given(postDtoMapper.toResponse(post)).willReturn(response);

      // When
      PostResponse result = postService.setPostVisible(request);

      // Then
      assertThat(result).isEqualTo(response);
      assertThat(post.isPublicAccess()).isTrue();
    }

    @Test
    @DisplayName("포스트가 없으면 DataNotFoundException이 발생한다")
    void setPostVisible_postNotFound_throwsDataNotFoundException() {
      // Given
      PostPublicRequest request = PostPublicRequest.builder().id(999L).publicStatus(true).build();
      given(postRepository.findById(999L)).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> postService.setPostVisible(request))
          .isInstanceOf(DataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("addPostTag")
  class AddPostTag {

    @Test
    @DisplayName("정상 요청이면 TagResponse를 반환한다")
    void addPostTag_validRequest_returnsTagResponse() {
      // Given
      Long postId = 1L;
      TagCreate tagCreate = TagCreate.builder().name("Java").build();
      Tag tag = Tag.builder().id(10L).name("Java").posts(new HashSet<>()).build();
      Post post = createPost(postId, true, false);
      TagResponse response = TagResponse.builder().id(10L).name("Java").postCount(1).build();

      given(tagService.createIfNotExists(tagCreate)).willReturn(tag);
      given(postRepository.findById(postId)).willReturn(Optional.of(post));
      given(postRepository.saveAndFlush(any())).willReturn(post);
      given(tagService.findById(tag.getId())).willReturn(tag);
      given(tagDtoMapper.toResponse(tag)).willReturn(response);

      // When
      TagResponse result = postService.addPostTag(postId, tagCreate);

      // Then
      assertThat(result).isEqualTo(response);
    }

    @Test
    @DisplayName("포스트가 없으면 DataNotFoundException이 발생한다")
    void addPostTag_postNotFound_throwsDataNotFoundException() {
      // Given
      Long postId = 999L;
      TagCreate tagCreate = TagCreate.builder().name("Java").build();
      Tag tag = Tag.builder().id(10L).name("Java").posts(new HashSet<>()).build();

      given(tagService.createIfNotExists(tagCreate)).willReturn(tag);
      given(postRepository.findById(postId)).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> postService.addPostTag(postId, tagCreate))
          .isInstanceOf(DataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("deletePostTag")
  class DeletePostTag {

    @Test
    @DisplayName("정상 요청이면 태그가 제거된 PostResponse를 반환한다")
    void deletePostTag_validRequest_returnsPostResponse() {
      // Given
      Long postId = 1L;
      Long tagId = 10L;
      Tag tag = Tag.builder().id(tagId).name("Java").posts(new HashSet<>()).build();
      Post post = createPost(postId, true, false);
      post.addTag(tag);
      PostResponse response = createPostResponse(postId, "제목");

      given(tagService.findById(tagId)).willReturn(tag);
      given(postRepository.findById(postId)).willReturn(Optional.of(post));
      given(postRepository.save(any())).willReturn(post);
      given(postDtoMapper.toResponse(post)).willReturn(response);

      // When
      PostResponse result = postService.deletePostTag(postId, tagId);

      // Then
      assertThat(result).isEqualTo(response);
    }

    @Test
    @DisplayName("포스트가 없으면 DataNotFoundException이 발생한다")
    void deletePostTag_postNotFound_throwsDataNotFoundException() {
      // Given
      Long postId = 999L;
      Long tagId = 10L;
      Tag tag = Tag.builder().id(tagId).name("Java").posts(new HashSet<>()).build();

      given(tagService.findById(tagId)).willReturn(tag);
      given(postRepository.findById(postId)).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> postService.deletePostTag(postId, tagId))
          .isInstanceOf(DataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("addFile")
  class AddFile {

    @Test
    @DisplayName("정상 요청이면 FileResponse를 반환한다")
    void addFile_validRequest_returnsFileResponse() throws IOException {
      // Given
      Long postId = 1L;
      String relativePath = "/uploads/test.png";
      Post post = createPost(postId, true, false);

      MultipartFile multipartFile = mock(MultipartFile.class);
      Resource resource = mock(Resource.class);
      FileCreate fileCreate = FileCreate.builder().postId(postId).file(multipartFile).build();
      FileResponse response = FileResponse.builder().id("1").originName("test.png").type("image/png").fileSize(1024L).build();

      given(postRepository.findById(postId)).willReturn(Optional.of(post));
      given(multipartFile.getOriginalFilename()).willReturn("test.png");
      given(multipartFile.getContentType()).willReturn("image/png");
      given(resource.contentLength()).willReturn(1024L);
      given(postRepository.saveAndFlush(any())).willReturn(post);
      given(fileDtoMapper.toResponse(any())).willReturn(response);

      // When
      FileResponse result = postService.addFile(postId, fileCreate, resource, relativePath);

      // Then
      assertThat(result).isEqualTo(response);
    }

    @Test
    @DisplayName("포스트가 없으면 DataNotFoundException이 발생한다")
    void addFile_postNotFound_throwsDataNotFoundException() throws IOException {
      // Given
      Long postId = 999L;
      MultipartFile multipartFile = mock(MultipartFile.class);
      Resource resource = mock(Resource.class);
      FileCreate fileCreate = FileCreate.builder().postId(postId).file(multipartFile).build();

      given(postRepository.findById(postId)).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> postService.addFile(postId, fileCreate, resource, "/path"))
          .isInstanceOf(DataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("removeFile")
  class RemoveFile {

    @Test
    @DisplayName("정상 요청이면 파일을 삭제한다")
    void removeFile_validRequest_removesFile() {
      // Given
      Long postId = 1L;
      Long fileId = 100L;
      File file = File.builder()
          .id(fileId).originName("test.png").type("image/png")
          .path("/uploads/test.png").fileSize(1024L).deleted(false).build();
      Set<File> files = new HashSet<>();
      files.add(file);
      Post post = Post.builder()
          .id(postId).subject("제목").body("본문").normalBody("본문")
          .publicAccess(true).mainPage(false).tags(new HashSet<>()).files(files).build();

      given(postRepository.findById(postId)).willReturn(Optional.of(post));

      // When
      postService.removeFile(postId, fileId);

      // Then
      then(postRepository).should().save(post);
      assertThat(post.getFiles()).doesNotContain(file);
    }

    @Test
    @DisplayName("포스트가 없으면 DataNotFoundException이 발생한다")
    void removeFile_postNotFound_throwsDataNotFoundException() {
      // Given
      given(postRepository.findById(999L)).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> postService.removeFile(999L, 100L))
          .isInstanceOf(DataNotFoundException.class);
    }

    @Test
    @DisplayName("파일이 없으면 DataNotFoundException이 발생한다")
    void removeFile_fileNotFound_throwsDataNotFoundException() {
      // Given
      Long postId = 1L;
      Post post = createPost(postId, true, false);
      given(postRepository.findById(postId)).willReturn(Optional.of(post));

      // When & Then
      assertThatThrownBy(() -> postService.removeFile(postId, 999L))
          .isInstanceOf(DataNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("getFilesByPostId")
  class GetFilesByPostId {

    @Test
    @DisplayName("포스트에 파일이 있으면 파일 목록을 반환한다")
    void getFilesByPostId_filesExist_returnsFileList() {
      // Given
      Long postId = 1L;
      File file = File.builder()
          .id(100L).originName("test.png").type("image/png")
          .path("/uploads/test.png").fileSize(1024L).deleted(false).build();
      Set<File> files = new HashSet<>();
      files.add(file);
      Post post = Post.builder()
          .id(postId).subject("제목").body("본문").normalBody("본문")
          .publicAccess(true).mainPage(false).tags(new HashSet<>()).files(files).build();
      FileResponse response = FileResponse.builder().id("100").originName("test.png").build();

      given(postRepository.findById(postId)).willReturn(Optional.of(post));
      given(fileDtoMapper.toResponse(file)).willReturn(response);

      // When
      List<FileResponse> result = postService.getFilesByPostId(postId);

      // Then
      assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("포스트가 없으면 DataNotFoundException이 발생한다")
    void getFilesByPostId_postNotFound_throwsDataNotFoundException() {
      // Given
      given(postRepository.findById(999L)).willReturn(Optional.empty());

      // When & Then
      assertThatThrownBy(() -> postService.getFilesByPostId(999L))
          .isInstanceOf(DataNotFoundException.class);
    }
  }
}
