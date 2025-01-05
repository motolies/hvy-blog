package kr.hvy.blog.modules.file.application.service;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

public abstract class AbstractFileManagementService {

  protected static Path rootLocation;

  @Value("${path.upload}")
  public void setRootLocation(String path) {
    rootLocation = Paths.get(path);
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

}
