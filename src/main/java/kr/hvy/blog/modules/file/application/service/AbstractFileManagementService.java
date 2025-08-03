package kr.hvy.blog.modules.file.application.service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import kr.hvy.blog.modules.file.domain.entity.File;
import kr.hvy.blog.modules.file.repository.FileRepository;
import kr.hvy.common.exception.DataNotFoundException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

public abstract class AbstractFileManagementService {

  protected Path rootLocation;
  protected FileRepository fileRepository;

  public AbstractFileManagementService(String rootLocation, FileRepository fileRepository) {
    this.rootLocation = Paths.get(rootLocation);
    this.fileRepository = fileRepository;
  }

  protected Resource loadAsResource(String fileName) throws Exception {
    try {
      // 불러올 때 폴더
      String realPath = rootLocation.toString().replace(java.io.File.separatorChar, '/') + java.io.File.separator + fileName;

      Path file = loadPath(realPath);
      Resource resource = new UrlResource(file.toUri());
      if (resource.exists() || resource.isReadable()) {
        return resource;
      } else {
        throw new Exception("Could not read file: " + fileName);
      }
    } catch (Exception e) {
      throw new Exception("Could not read file: " + fileName);
    }
  }


  protected Path loadPath(String fileName) {
    return rootLocation.resolve(fileName);
  }

  protected File findById(Long id) {
    return fileRepository.findById(id)
        .orElseThrow(() -> new DataNotFoundException("Not Found File."));
  }

}
