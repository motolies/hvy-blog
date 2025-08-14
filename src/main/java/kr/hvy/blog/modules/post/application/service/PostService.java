package kr.hvy.blog.modules.post.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
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
import kr.hvy.blog.modules.post.application.specification.PostAuthoritySpecification;
import kr.hvy.blog.modules.post.application.specification.PostUpdateSpecification;
import kr.hvy.blog.modules.post.domain.entity.Post;
import kr.hvy.blog.modules.post.mapper.PostDtoMapper;
import kr.hvy.blog.modules.post.repository.PostRepository;
import kr.hvy.blog.modules.post.repository.mapper.PostMapper;
import kr.hvy.blog.modules.tag.application.dto.TagCreate;
import kr.hvy.blog.modules.tag.application.dto.TagResponse;
import kr.hvy.blog.modules.tag.application.service.TagService;
import kr.hvy.blog.modules.tag.domain.Tag;
import kr.hvy.blog.modules.tag.mapper.TagDtoMapper;
import kr.hvy.blog.modules.tag.repository.TagRepository;
import kr.hvy.common.domain.dto.DeleteResponse;
import kr.hvy.common.exception.DataNotFoundException;
import kr.hvy.common.specification.Specification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PostService {

  @PersistenceContext
  private EntityManager entityManager;

  private final TagService tagService;
  private final FileDtoMapper fileDtoMapper;

  private final TagDtoMapper tagDtoMapper;
  private final PostDtoMapper postDtoMapper;
  private final PostRepository postRepository;
  private final TagRepository tagRepository;
  private final PostMapper postMapper;
  private final CategoryRepository categoryRepository;


  private void deleteByTempPosts() {
    List<Post> tempPosts = postRepository.findBySubjectAndBody("", "");
    postRepository.deleteAll(tempPosts);
    postRepository.flush();
  }


  private Post findById(Long id) {
    return postRepository.findById(id)
        .orElseThrow(() -> new DataNotFoundException("Not Found Post."));
  }

  public Post findByIdCheckAuthority(Long id) {
    Post post = findById(id);

    Specification.validate(PostAuthoritySpecification::new, post);

    return post;
  }

  public Post findByMain() {
    List<Post> list = postRepository.findByMainPage(true);
    if (list.isEmpty()) {
      return postRepository.findTopByPublicAccessOrderById(true)
          .orElseThrow(() -> new DataNotFoundException("Not Found Public Post."));
    } else {
      return list.getFirst();
    }
  }

  public PostPrevNextResponse findPrevNextById(Boolean isAdmin, Long id) {
    return postMapper.findPrevNextById(isAdmin, id);
  }

  public List<Long> findByPublicPosts() {
    return postMapper.findByPublicPosts();
  }

  public List<PostNoBodyResponse> findBySearchObject(SearchObject searchObject) {
    return postMapper.findBySearchObject(searchObject);
  }

  public void setMainPost(Long id) {
    postMapper.setMainPost(id);
  }


  public PostResponse create(PostCreate createDto) {
    deleteByTempPosts();
    Post post = postDtoMapper.toDomain(createDto);

    Category category = categoryRepository.findById(createDto.getCategoryId())
        .orElseThrow(() -> new DataNotFoundException("Not Found Category."));
    post.setCategory(category);

    Post savedPost = postRepository.save(post);
    return postDtoMapper.toResponse(savedPost);
  }


  public PostResponse update(Long id, PostUpdate updateDto) {
    Specification.validate(PostUpdateSpecification::new, updateDto);

    Post post = findById(id);
    Category category = categoryRepository.findById(updateDto.getCategoryId())
        .orElseThrow(() -> new DataNotFoundException("Not Found Category."));
    post.update(updateDto, category);

    return postDtoMapper.toResponse(post);
  }

  public DeleteResponse<Long> delete(Long id) {
    postRepository.deleteById(id);
    return DeleteResponse.<Long>builder()
        .id(id).build();
  }

  public PostResponse setPostVisible(PostPublicRequest postPublicRequest) {
    Post post = findById(postPublicRequest.getId());
    post.setPublicAccess(postPublicRequest.isPublicStatus());
    return postDtoMapper.toResponse(post);
  }


  public TagResponse addPostTag(Long postId, TagCreate tagCreate) {
    // todo 태그 저장하는 부분도 같이 손봐야 함
    TagResponse tagResponse = tagService.create(tagCreate);
    Tag tag = tagRepository.findById(tagResponse.getId())
        .orElseThrow(() -> new DataNotFoundException("Not Found Tag."));
    Post post = postRepository.findById(postId)
        .orElseThrow(() -> new DataNotFoundException("Not Found Post."));
    post.addTag(tag);
    postRepository.saveAndFlush(post);
    return tagDtoMapper.toResponse(tagService.findById(tag.getId()));
  }

  public PostResponse deletePostTag(Long postId, Long tagId) {
    Tag tag = tagRepository.findById(tagId)
        .orElseThrow(() -> new DataNotFoundException("Not Found Tag."));
    Post post = postRepository.findById(postId)
        .orElseThrow(() -> new DataNotFoundException("Not Found Post."));
    post.removeTag(tag);
    postRepository.save(post);
    return postDtoMapper.toResponse(post);
  }


  public FileResponse addFile(Long postId, FileCreate fileCreate, Resource resource, String relativePath) {
    Post post = postRepository.findById(postId)
        .orElseThrow(() -> new DataNotFoundException("Post not found with id: " + postId));

    try {
      post.addFile(
          fileCreate.getFile().getOriginalFilename(),
          fileCreate.getFile().getContentType(),
          relativePath,
          resource.contentLength()
      );

      postRepository.saveAndFlush(post);
      // todo ; check 필요
//      entityManager.refresh(post);

      // refresh 후 실제로 저장된 파일을 찾아서 응답 생성 (ID가 생성된 상태)
      File savedFile = post.getFiles().stream()
          .filter(f -> f.getPath().equals(relativePath))
          .findFirst()
          .orElseThrow(() -> new DataNotFoundException("File not found after save"));

      return fileDtoMapper.toResponse(savedFile);
    } catch (Exception e) {
      throw new RuntimeException("Failed to create file", e);
    }
  }

  public void removeFile(Long postId, Long fileId) {
    Post post = postRepository.findById(postId)
        .orElseThrow(() -> new DataNotFoundException("Post not found with id: " + postId));

    File file = post.getFiles().stream()
        .filter(f -> f.getId().equals(fileId))
        .findFirst()
        .orElseThrow(() -> new DataNotFoundException("File not found with id: " + fileId));

    post.removeFile(file);
    postRepository.save(post);
  }

  public List<FileResponse> getFilesByPostId(Long postId) {
    Post post = postRepository.findById(postId)
        .orElseThrow(() -> new DataNotFoundException("Post not found with id: " + postId));

    return post.getFiles().stream()
        .map(fileDtoMapper::toResponse)
        .toList();
  }


}
